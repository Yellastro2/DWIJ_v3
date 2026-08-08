package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.dao.SongDao
import com.yellastrodev.dwij.data.dao.SongMatchDao
import com.yellastrodev.dwij.data.dao.SongWithInstances
import com.yellastrodev.dwij.data.entities.MusicSource
import com.yellastrodev.dwij.data.entities.SongMatchCandidateEntity
import com.yellastrodev.yamusicsdk.YamLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Хранит подсказки resolver-а и последовательно сканирует только новые версии [SongWithInstances]. */
class SongMatchRepository(
    private val songDao: SongDao,
    private val matchDao: SongMatchDao,
    private val logger: YamLogger,
) {


    private val resolver: SongMatchResolver = SongMatchResolver(logger)
    private val scanMutex = Mutex()

    /** Все найденные пары: сначала ожидающие решения, затем уже обработанные. */
    val candidates: Flow<List<SongMatchCandidateEntity>> =
        matchDao.observeAllCandidates()

    val pendingCandidates: Flow<List<SongMatchCandidateEntity>> =
        matchDao.observePendingCandidates()

    fun pendingCandidatesForSong(songId: String): Flow<List<SongMatchCandidateEntity>> =
        matchDao.observePendingCandidatesForSong(songId)

    /** Оставляет пользовательское решение «это разные песни» постоянным. */
    suspend fun rejectCandidate(firstSongId: String, secondSongId: String) {
        val (first, second) = orderedIds(firstSongId, secondSongId)
        matchDao.rejectCandidate(first, second)
    }

    /** Запускает один наблюдатель: новая Song с resolverVersion=0 сама попадёт в скан. */
    fun start(scope: CoroutineScope): Job = songDao
        .observeUnscannedSongCount(CURRENT_RESOLVER_VERSION)
        .distinctUntilChanged()
        .filter { count -> count > 0 }
        .onEach { count ->
            logger.debug(TAG, "[start] В очереди resolver-а песен=$count")
            scanUnprocessedSongs()
        }
        .launchIn(scope)

    /** Пост-скан после обновления и инкрементальный скан используют один механизм. */
    suspend fun scanUnprocessedSongs(): Unit = scanMutex.withLock {
        var scannedCount = 0
        while (true) {
            val batch = songDao.getUnscannedSongs(
                resolverVersion = CURRENT_RESOLVER_VERSION,
                limit = SCAN_BATCH_SIZE,
            )
            if (batch.isEmpty()) break
            val allSongs = songDao.getAllSongs()
            logger.debug(
                TAG,
                "[scanUnprocessedSongs] Начата пачка=${batch.size}, " +
                    "всего песен в снимке=${allSongs.size}",
            )
            var batchComparisonCount = 0
            var batchCandidateCount = 0
            batch.forEach { song ->
                try {
                    val search = findCandidates(song, allSongs)
                    batchComparisonCount += search.comparedPairs
                    batchCandidateCount += search.candidates.size
                    matchDao.replacePendingCandidatesForSong(
                        song.song.songId,
                        search.candidates,
                    )
                    songDao.markResolverVersionIfUnchanged(
                        songId = song.song.songId,
                        expectedMatchKey = song.song.matchKey,
                        resolverVersion = CURRENT_RESOLVER_VERSION,
                    )
                    scannedCount += 1
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    logger.error(
                        TAG,
                        "[scanUnprocessedSongs] Ошибка songId=${song.song.songId}",
                        error,
                    )
                    // Повреждённая запись не должна зациклить весь фоновый скан.
                    songDao.markResolverVersion(
                        songId = song.song.songId,
                        resolverVersion = CURRENT_RESOLVER_VERSION,
                    )
                }
            }
            logger.debug(
                TAG,
                "[scanUnprocessedSongs] Пачка завершена: " +
                    "сравнений=$batchComparisonCount, кандидатов=$batchCandidateCount",
            )
        }
        val pendingCandidateCount = matchDao.getPendingCandidateCount()
        logger.debug(
            TAG,
            "[scanUnprocessedSongs] Проверено песен=$scannedCount, " +
                "ожидающих совпадений=$pendingCandidateCount, " +
                "resolverVersion=$CURRENT_RESOLVER_VERSION",
        )
        Unit
    }

    private fun findCandidates(
        song: SongWithInstances,
        allSongs: List<SongWithInstances>,
    ): SongCandidateSearch {
        var comparedPairs = 0
        val candidates = allSongs.mapNotNull { other ->
            if (song.song.songId == other.song.songId || !isCrossSourcePair(song, other)) {
                return@mapNotNull null
            }
            comparedPairs += 1
            val score = resolver.compare(song.song, other.song) ?: return@mapNotNull null
            val (firstId, secondId) = orderedIds(song.song.songId, other.song.songId)
            SongMatchCandidateEntity(
                firstSongId = firstId,
                secondSongId = secondId,
                titleSimilarity = score.titleSimilarity,
                artistSimilarity = score.artistSimilarity,
                score = score.total,
                resolverVersion = CURRENT_RESOLVER_VERSION,
            )
        }.distinctBy { candidate -> candidate.firstSongId to candidate.secondSongId }
        return SongCandidateSearch(
            candidates = candidates,
            comparedPairs = comparedPairs,
        )
    }

    /** На первом этапе предлагаем только пары Яндекс ↔ локальный файл. */
    private fun isCrossSourcePair(
        first: SongWithInstances,
        second: SongWithInstances,
    ): Boolean {
        val firstSources = first.instances.mapTo(mutableSetOf()) { instance -> instance.source }
        val secondSources = second.instances.mapTo(mutableSetOf()) { instance -> instance.source }
        if (firstSources.intersect(secondSources).isNotEmpty()) return false
        val combined = firstSources + secondSources
        return MusicSource.YANDEX.name in combined && MusicSource.LOCAL.name in combined
    }

    private fun orderedIds(firstSongId: String, secondSongId: String): Pair<String, String> =
        if (firstSongId <= secondSongId) {
            firstSongId to secondSongId
        } else {
            secondSongId to firstSongId
        }

    companion object {
        const val CURRENT_RESOLVER_VERSION = 1
        private const val SCAN_BATCH_SIZE = 32
        private const val TAG = "SongMatchRepository"
    }
}

private data class SongCandidateSearch(
    val candidates: List<SongMatchCandidateEntity>,
    val comparedPairs: Int,
)
