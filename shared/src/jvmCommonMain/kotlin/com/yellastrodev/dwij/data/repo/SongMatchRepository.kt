package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.dao.SongDao
import com.yellastrodev.dwij.data.dao.SongMatchDao
import com.yellastrodev.dwij.data.dao.SongWithInstances
import com.yellastrodev.dwij.data.entities.MusicSource
import com.yellastrodev.dwij.data.entities.SongMatchCandidateEntity
import com.yellastrodev.dwij.storage.LocalKeyValueStore
import com.yellastrodev.yamusicsdk.YamLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Предлагает межсурсные совпадения и при явном включении автоматически объединяет ожидающие пары. */
class SongMatchRepository(
    private val songDao: SongDao,
    private val matchDao: SongMatchDao,
    private val logger: YamLogger,
    private val settings: LocalKeyValueStore? = null,
    private val resolver: SongMatchResolver = SongMatchResolver(logger),
) {


    private val scanMutex = Mutex()
    private val mutableScanEnabled = MutableStateFlow(settings?.getBoolean(KEY_SCAN_ENABLED) ?: true)
    val scanEnabled = mutableScanEnabled.asStateFlow()
    private val mutableAutoMergeEnabled = MutableStateFlow(settings?.getBoolean(KEY_AUTO_MERGE_ENABLED) ?: false)
    val autoMergeEnabled = mutableAutoMergeEnabled.asStateFlow()

    /** Сохраняет автослияние; интерфейс запрашивает подтверждение перед включением. Отключение не разъединяет группы. */
    fun setAutoMergeEnabled(enabled: Boolean) {
        settings?.edit { putBoolean(KEY_AUTO_MERGE_ENABLED, enabled) }
        mutableAutoMergeEnabled.value = enabled
        logger.info(TAG, "[setAutoMergeEnabled] Автослияние мультисурсов ${if (enabled) "включено" else "выключено"}")
    }

    /** Сохраняет выбор; наблюдатель отменяет текущий скан либо возобновляет обработку оставшихся песен. */
    fun setScanEnabled(enabled: Boolean) {
        if (mutableScanEnabled.value == enabled) return
        settings?.edit { putBoolean(KEY_SCAN_ENABLED, enabled) }
        mutableScanEnabled.value = enabled
        logger.debug(TAG, "[setScanEnabled] Сканирование мультисурсов ${if (enabled) "включено" else "выключено"}")
    }

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

    /** Независимо наблюдает скан и автослияние; накопленные предложения обрабатываются и при выключенном скане. */
    fun start(
        scope: CoroutineScope,
        onMerged: suspend (sourceSongIds: Set<String>, mergedSongId: String) -> Unit = { _, _ -> },
    ): Job = scope.launch(Dispatchers.IO) {
        launch {
            autoMergeEnabled.collectLatest { enabled ->
                if (enabled) pendingCandidates.collect { mergePendingCandidates(it, onMerged) }
            }
        }
        launch {
            scanEnabled.collectLatest { enabled ->
                if (enabled) {
                    songDao.observeUnscannedSongCount(CURRENT_RESOLVER_VERSION)
                        .distinctUntilChanged()
                        .filter { count -> count > 0 }
                        .collect { count ->
                            logger.debug(TAG, "[start] В очереди resolver-а песен=$count")
                            scanUnprocessedSongs()
                        }
                }
            }
        }
    }

    /** Сериализует слияние со сканом, повторно проверяет решения в Room и обновляет текущую очередь после каждой пары. */
    private suspend fun mergePendingCandidates(
        candidates: List<SongMatchCandidateEntity>,
        onMerged: suspend (Set<String>, String) -> Unit,
    ) = scanMutex.withLock {
        candidates.forEach { candidate ->
            currentCoroutineContext().ensureActive()
            if (!autoMergeEnabled.value) return@withLock
            try {
                val songs = songDao.getSongs(listOf(candidate.firstSongId, candidate.secondSongId))
                    .associateBy { it.song.songId }
                val first = songs[candidate.firstSongId] ?: return@forEach
                val second = songs[candidate.secondSongId] ?: return@forEach
                val includesVk = (first.instances + second.instances).any { it.source == MusicSource.VK.name }
                if (resolver.compare(first.song, second.song, checkRecording = includesVk) == null) return@forEach
                val mergedId = matchDao.mergePendingCandidate(songDao, candidate.firstSongId, candidate.secondSongId)
                    ?: return@forEach
                withContext(NonCancellable) {
                    onMerged(setOf(candidate.firstSongId, candidate.secondSongId), mergedId)
                }
                logger.info(TAG, "[mergePendingCandidates] Объединены варианты ${candidate.firstSongId} и ${candidate.secondSongId}")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.error(TAG, "[mergePendingCandidates] Не удалось объединить варианты ${candidate.firstSongId} и ${candidate.secondSongId}", error)
            }
        }
    }

    /** Сканирует только при включённой настройке; незавершённые песни остаются для следующего запуска. */
    suspend fun scanUnprocessedSongs(): Unit = scanMutex.withLock {
        if (!scanEnabled.value) return@withLock
        var scannedCount = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!scanEnabled.value) return@withLock
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
                    currentCoroutineContext().ensureActive()
                    if (!scanEnabled.value) return@withLock
                    val search = findCandidates(song, allSongs) ?: return@withLock
                    if (!scanEnabled.value) return@withLock
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

    /** Сравнивает независимые группы, проверяя отмену и переключатель между парами; null означает прерванный скан. */
    private suspend fun findCandidates(
        song: SongWithInstances,
        allSongs: List<SongWithInstances>,
    ): SongCandidateSearch? {
        var comparedPairs = 0
        val candidates = allSongs.mapNotNull { other ->
            currentCoroutineContext().ensureActive()
            if (!scanEnabled.value) return null
            if (song.song.songId == other.song.songId || !isCrossSourcePair(song, other)) {
                return@mapNotNull null
            }
            comparedPairs += 1
            val includesVk = (song.instances + other.instances).any {
                it.source == MusicSource.VK.name
            }
            val score = resolver.compare(
                song.song,
                other.song,
                checkRecording = includesVk,
            ) ?: return@mapNotNull null
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

    /** Дополняет группу новым источником, исключая дубли внутри одного источника и пересекающихся групп. */
    private fun isCrossSourcePair(
        first: SongWithInstances,
        second: SongWithInstances,
    ): Boolean {
        val supportedSources = MusicSource.entries.mapTo(mutableSetOf()) { it.name }
        val firstSources = first.instances.mapTo(mutableSetOf()) { instance -> instance.source }
            .intersect(supportedSources)
        val secondSources = second.instances.mapTo(mutableSetOf()) { instance -> instance.source }
            .intersect(supportedSources)
        if (firstSources.isEmpty() || secondSources.isEmpty()) return false
        if (firstSources.intersect(secondSources).isNotEmpty()) return false
        return true
    }

    private fun orderedIds(firstSongId: String, secondSongId: String): Pair<String, String> =
        if (firstSongId <= secondSongId) {
            firstSongId to secondSongId
        } else {
            secondSongId to firstSongId
        }

    companion object {
        const val CURRENT_RESOLVER_VERSION = 2
        private const val SCAN_BATCH_SIZE = 32
        private const val TAG = "SongMatchRepository"
        private const val KEY_SCAN_ENABLED = "multi_source_scan_enabled"
        private const val KEY_AUTO_MERGE_ENABLED = "multi_source_auto_merge_enabled"
    }
}

private data class SongCandidateSearch(
    val candidates: List<SongMatchCandidateEntity>,
    val comparedPairs: Int,
)
