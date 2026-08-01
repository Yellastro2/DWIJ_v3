package com.yellastrodev.dwij.data.repo

import android.util.Log
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.dao.dTrackDao
import com.yellastrodev.dwij.data.source.TrackRemoteSource
import com.yellastrodev.dwij.data.entities.dPlaylistTrack
import com.yellastrodev.dwij.data.entities.dYaTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


class TrackRepository(
    private val remote: TrackRemoteSource,
    private val local: dTrackDao
) {

    val TAG = "TrackRepository"
    private val _tracks = MutableStateFlow<Map<String, dYaTrack>>(emptyMap())
    val tracks: StateFlow<Map<String, dYaTrack>> = _tracks
    private val loadMutex = Mutex()

    suspend fun refreshTrackLocaly(trackId: String): DataResult<dYaTrack> {
        return try {
            val track = local.getTrack(trackId)
                ?: return DataResult.Failure(
                    DataError.NotFound("track", trackId)
                )
            _tracks.update { current ->
                current + (trackId to track)
            }
            DataResult.Success(track)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DataResult.Failure(DataError.Storage(error))
        }
    }

    suspend fun refreshTrackListLocaly(trackIds: List<String>) {
        val tracks = local.getTracks(trackIds)
        _tracks.update { current ->
            current + tracks.associateBy { it.id }
        }
    }

    suspend fun getTrack(trackId: String): DataResult<dYaTrack> {
        if (trackId.isBlank()) {
            return DataResult.Failure(
                DataError.InvalidData("trackId не должен быть пустым")
            )
        }
        return loadMutex.withLock {
            try {
                _tracks.value[trackId]
                    ?.takeIf { it.albums.isNotEmpty() }
                    ?.let { return@withLock DataResult.Success(it) }

                val localTrack = local.getTrack(trackId)
                if (localTrack != null && localTrack.albums.isNotEmpty()) {
                    _tracks.update { it + (trackId to localTrack) }
                    return@withLock DataResult.Success(localTrack)
                }

                when (val result = remote.fetchTracks(listOf(trackId))) {
                    is DataResult.Success -> {
                        val remoteTrack = result.value.firstOrNull()
                            ?: return@withLock DataResult.Failure(
                                DataError.NotFound("track", trackId)
                            )
                        local.insert(remoteTrack)
                        _tracks.update { it + (trackId to remoteTrack) }
                        DataResult.Success(remoteTrack)
                    }
                    is DataResult.Failure -> result
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                DataResult.Failure(DataError.Storage(error))
            }
        }
    }

    suspend fun putTracks(trackList: List<dYaTrack>) {

        local.insertAll(trackList)

        val updated = mutableMapOf<String, dYaTrack>()
        trackList.forEach { track ->
            if (!_tracks.value.containsKey(track.id)) {
                updated[track.id] = track
            }
        }

        if (updated.isNotEmpty()) {
            Log.d(TAG, "putTracks( updateSize=${updated.size})")
            refreshTrackListLocaly(updated.keys.toList())
            Log.d(TAG, "putTracks(valueSize=${_tracks.value.size})")


        }
    }

    /**
     * Возвращает Flow, который будет выдавать список треков,
     * соответствующий переданному списку [shorts].
     */
    fun tracksFlow(shorts: List<dPlaylistTrack>): Flow<List<dYaTrack>> {
        Log.d(TAG, "tracksFlow() size=${shorts.size}")
        val ids = shorts.map { it.trackId }

        return _tracks
            .onStart {
                when (val result = loadTracks(ids)) {
                    is DataResult.Success -> Unit
                    is DataResult.Failure -> Log.e(
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

    suspend fun loadTracks(
        trackIds: List<String>
    ): DataResult<List<dYaTrack>> = loadMutex.withLock {
        if (trackIds.isEmpty()) {
            return@withLock DataResult.Success(emptyList())
        }
        try {
            val requestedIds = trackIds.distinct()
            val localTracks = local.getTracks(requestedIds)
            if (localTracks.isNotEmpty()) {
                _tracks.update {
                    it + localTracks.associateBy(dYaTrack::id)
                }
            }

            val remoteIds = requestedIds.filter { id ->
                val track = _tracks.value[id]
                track == null || track.albums.isEmpty()
            }
            if (remoteIds.isNotEmpty()) {
                Log.d(TAG, "[loadTracks] Догружаем ${remoteIds.size} треков")
                when (val result = remote.fetchTracks(remoteIds)) {
                    is DataResult.Success -> {
                        local.insertAll(result.value)
                        _tracks.update {
                            it + result.value.associateBy(dYaTrack::id)
                        }
                    }
                    is DataResult.Failure -> return@withLock result
                }
            }

            val missingIds = requestedIds.filterNot(_tracks.value::containsKey)
            if (missingIds.isNotEmpty()) {
                return@withLock DataResult.Failure(
                    DataError.NotFound(
                        entity = "tracks",
                        id = missingIds.joinToString(",")
                    )
                )
            }
            DataResult.Success(trackIds.mapNotNull(_tracks.value::get))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DataResult.Failure(DataError.Storage(error))
        }
    }

    suspend fun getTrackBytes(trackId: String): DataResult<ByteArray> {
        return when (val result = getTrack(trackId)) {
            is DataResult.Success -> remote.fetch(result.value)
            is DataResult.Failure -> result
        }
    }
    
}
