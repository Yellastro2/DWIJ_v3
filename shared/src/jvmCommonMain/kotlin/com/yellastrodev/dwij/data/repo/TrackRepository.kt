package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.dao.dTrackDao
import com.yellastrodev.dwij.data.source.TrackRemoteSource
import com.yellastrodev.dwij.data.entities.dPlaylistTrack
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.yamusicsdk.YamLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Отдаёт треки из Room немедленно и лениво освежает их сетевые метаданные по TTL.
 * Проверка запускается только для явно запрошенного трека или набора треков.
 */
class TrackRepository(
    private val remote: TrackRemoteSource,
    private val local: dTrackDao,
    private val songRepository: SongRepository,
    private val scope: CoroutineScope,
    private val logger: YamLogger
) {

    val TAG = "TrackRepository"
    private val _tracks = MutableStateFlow<Map<String, dYaTrack>>(emptyMap())
    val tracks: StateFlow<Map<String, dYaTrack>> = _tracks
    private val loadMutex = Mutex()
    private val refreshingTrackIds = mutableSetOf<String>()

    suspend fun refreshTrackLocaly(trackId: String): DataResult<dYaTrack> {
        return try {
            val track = local.getTrack(trackId)
                ?: return DataResult.Failure(
                    DataError.NotFound("track", trackId)
                )
            publishTracks(listOf(track))
            songRepository.registerYandexTracks(listOf(track))
            DataResult.Success(track)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DataResult.Failure(DataError.Storage(error))
        }
    }

    suspend fun refreshTrackListLocaly(trackIds: List<String>) {
        val tracks = local.getTracks(trackIds)
        publishTracks(tracks)
    }

    suspend fun getTrack(trackId: String): DataResult<dYaTrack> {
        if (trackId.isBlank()) {
            return DataResult.Failure(
                DataError.InvalidData("trackId не должен быть пустым")
            )
        }
        return when (val result = loadTracks(listOf(trackId))) {
            is DataResult.Success -> result.value.firstOrNull()
                ?.let { DataResult.Success(it) }
                ?: DataResult.Failure(DataError.NotFound("track", trackId))
            is DataResult.Failure -> result
        }
    }

    /** Сохраняет полученные извне треки и публикует только видимые изменения. */
    suspend fun putTracks(trackList: List<dYaTrack>) {
        local.insertAll(trackList)
        val storedTracks = local.getTracks(trackList.map(dYaTrack::id))
        publishTracks(storedTracks)
        songRepository.registerYandexTracks(storedTracks)
        logger.debug(TAG, "[putTracks] Сохранено=${storedTracks.size}, в памяти=${_tracks.value.size}")
    }

    /**
     * Возвращает Flow, который будет выдавать список треков,
     * соответствующий переданному списку [shorts].
     */
    fun tracksFlow(shorts: List<dPlaylistTrack>): Flow<List<dYaTrack>> {
        logger.debug(TAG, "tracksFlow() size=${shorts.size}")
        val ids = shorts.map { it.trackId }

        return _tracks
            .onStart {
                when (val result = loadTracks(ids)) {
                    is DataResult.Success -> Unit
                    is DataResult.Failure -> logger.error(
                        TAG,
                        "[tracksFlow] Треки не загружены: ${result.error}"
                    )
                }
            }
            .map { cache ->
                ids.mapNotNull { cache[it] }
            }
            .distinctUntilChanged()
    }

    /**
     * Собирает треки из текущего кеша треков в _tracks.value,
     * а недостающие в нем айдишки запрашивает в ремот
     */
    suspend fun getTracks(
        trackIds: List<String>
    ): DataResult<List<dYaTrack>> = loadTracks(trackIds)

    /**
     * Сначала читает запрошенные треки из Room. Недостающие получает синхронно,
     * а существующие с просроченной доступностью освежает в фоне пачками.
     */
    suspend fun loadTracks(
        trackIds: List<String>
    ): DataResult<List<dYaTrack>> {
        if (trackIds.isEmpty()) {
            return DataResult.Success(emptyList())
        }

        var staleIds = emptyList<String>()
        val result = loadMutex.withLock {
            try {
                val requestedIds = trackIds.distinct()
                val localTracks = local.getTracks(requestedIds)
                publishTracks(localTracks)
                val localById = localTracks.associateBy(dYaTrack::id)

                val missingIds = requestedIds.filter { id ->
                    val track = localById[id]
                    track == null || track.albums.isEmpty()
                }
                if (missingIds.isNotEmpty()) {
                    logger.debug(TAG, "[loadTracks] Догружаем отсутствующие треки=${missingIds.size}")
                    when (val remoteResult = fetchAndStoreTracks(missingIds)) {
                        is DataResult.Success -> Unit
                        is DataResult.Failure -> return@withLock remoteResult
                    }
                }

                val unresolvedIds = requestedIds.filterNot(_tracks.value::containsKey)
                if (unresolvedIds.isNotEmpty()) {
                    return@withLock DataResult.Failure(
                        DataError.NotFound(
                            entity = "tracks",
                            id = unresolvedIds.joinToString(",")
                        )
                    )
                }

                val staleBefore = System.currentTimeMillis() - AVAILABILITY_TTL_MILLIS
                staleIds = localTracks
                    .asSequence()
                    .filter { it.id !in missingIds }
                    .filter { it.availabilityCheckedAt <= staleBefore }
                    .map(dYaTrack::id)
                    .toList()

                val resolvedTracks = trackIds.mapNotNull(_tracks.value::get)
                songRepository.registerYandexTracks(resolvedTracks)
                DataResult.Success(resolvedTracks)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                DataResult.Failure(DataError.Storage(error))
            }
        }

        if (result is DataResult.Success && staleIds.isNotEmpty()) {
            refreshStaleTracksInBackground(staleIds)
        }
        return result
    }

    /** Запускает одну фоновую проверку для каждого просроченного ID. */
    private fun refreshStaleTracksInBackground(trackIds: List<String>) {
        scope.launch {
            val claimedIds = loadMutex.withLock {
                trackIds.filter(refreshingTrackIds::add)
            }
            if (claimedIds.isEmpty()) return@launch

            logger.debug(TAG, "[refreshStaleTracksInBackground] Просрочено=${claimedIds.size}")
            try {
                when (val result = fetchAndStoreTracks(claimedIds)) {
                    is DataResult.Success -> {
                        songRepository.registerYandexTracks(result.value)
                        logger.debug(
                            TAG,
                            "[refreshStaleTracksInBackground] Обновлено=${result.value.size}"
                        )
                    }
                    is DataResult.Failure -> logger.warning(
                        TAG,
                        "[refreshStaleTracksInBackground] Обновление не выполнено: ${result.error}"
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.error(TAG, "[refreshStaleTracksInBackground] Ошибка обновления", error)
            } finally {
                loadMutex.withLock {
                    refreshingTrackIds.removeAll(claimedIds.toSet())
                }
            }
        }
    }

    /** Получает метаданные пачками и сохраняет обновления в Room. */
    private suspend fun fetchAndStoreTracks(
        trackIds: List<String>
    ): DataResult<List<dYaTrack>> {
        val fetchedTracks = mutableListOf<dYaTrack>()
        for (batch in trackIds.distinct().chunked(REMOTE_BATCH_SIZE)) {
            when (val result = remote.fetchTracks(batch)) {
                is DataResult.Success -> {
                    local.insertAll(result.value)
                    val storedTracks = local.getTracks(result.value.map(dYaTrack::id))
                    publishTracks(storedTracks)
                    fetchedTracks += storedTracks
                }
                is DataResult.Failure -> return result
            }
        }
        return DataResult.Success(fetchedTracks)
    }

    /** Не меняет StateFlow, если обновилась только служебная отметка TTL. */
    private fun publishTracks(tracks: List<dYaTrack>) {
        if (tracks.isEmpty()) return
        _tracks.update { current ->
            val changedTracks = tracks.filter { track ->
                val previous = current[track.id]
                previous == null || !previous.hasSamePublishedContent(track)
            }
            if (changedTracks.isEmpty()) {
                current
            } else {
                current.toMutableMap().apply {
                    changedTracks.forEach { track -> put(track.id, track) }
                }
            }
        }
    }

    private fun dYaTrack.hasSamePublishedContent(other: dYaTrack): Boolean =
        id == other.id &&
            title == other.title &&
            available == other.available &&
            ogImageUri == other.ogImageUri &&
            coverUri == other.coverUri &&
            durationMs == other.durationMs &&
            previewDurationMs == other.previewDurationMs &&
            storageDir == other.storageDir &&
            fileSize == other.fileSize &&
            trackSource == other.trackSource &&
            artists == other.artists &&
            albums == other.albums &&
            playlists == other.playlists

    suspend fun getTrackBytes(trackId: String): DataResult<ByteArray> {
        return when (val result = getTrack(trackId)) {
            is DataResult.Success -> remote.fetch(result.value)
            is DataResult.Failure -> result
        }
    }

    private companion object {
        const val AVAILABILITY_TTL_MILLIS = 12L * 60L * 60L * 1_000L
        const val REMOTE_BATCH_SIZE = 100
    }
}
