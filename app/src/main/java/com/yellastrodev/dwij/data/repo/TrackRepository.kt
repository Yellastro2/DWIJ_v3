package com.yellastrodev.dwij.data.repo

import android.util.Log
import com.yellastrodev.dwij.data.source.TrackLocalSource
import com.yellastrodev.dwij.data.source.TrackRemoteSource
import com.yellastrodev.dwij.data.entities.dPlaylistTrack
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.yandexmusiclib.kot_utils.yTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class TrackRepository(
    private val remote: TrackRemoteSource,
    private val local: TrackLocalSource
) {

    val TAG = "TrackRepository"
    private val _tracks = MutableStateFlow<Map<String, dYaTrack>>(emptyMap())
    val tracks: StateFlow<Map<String, dYaTrack>> = _tracks


    init {
        GlobalScope.launch(Dispatchers.IO) {
            local.getAll().forEach { track ->
                _tracks.value = _tracks.value + (track.id to track)
            }
        }
    }

    suspend fun putTracks(trackList: List<dYaTrack>) {
//        val updated = _tracks.value.toMutableMap()
        val updated = mutableMapOf<String, dYaTrack>()
        trackList.forEach { track ->
            if (!_tracks.value.containsKey(track.id)) {
                updated[track.id] = track
            }
        }

        if (updated.isNotEmpty()) {
            Log.d(TAG, "putTracks( updateSize=${updated.size})")
            _tracks.value = _tracks.value + updated
            Log.d(TAG, "putTracks(valueSize=${_tracks.value.size})")

            GlobalScope.launch(Dispatchers.IO) {
                local.saveAll(trackList)
            }
        }
    }

    /**
     * Возвращает Flow, который будет выдавать список треков,
     * соответствующий переданному списку [shorts].
     */
    fun tracksFlow(shorts: List<dPlaylistTrack>): Flow<List<dYaTrack>> =
        _tracks
            .map { cache ->
                shorts.mapNotNull { cache[it.trackId] }
            }
            .distinctUntilChanged()

    /**
     * Собирает треки из текущего кеша треков в _tracks.value,
     * а недостающие в нем айдишки запрашивает в ремот
     */
    suspend fun getTracks(trackIds: List<String>): List<dYaTrack> {
        Log.d(TAG, "getTracks(size=${trackIds.size})")
        val updated = ArrayList<dYaTrack>()
        val missing = mutableListOf<String>()
        trackIds.forEach { trackId ->
            if (!_tracks.value.containsKey(trackId)) {
                missing.add(trackId)
            } else {
                updated.add(_tracks.value[trackId]!!)
            }
        }
        Log.d(TAG, "missing=${missing.size}, updated=${updated.size}")
        if (missing.isNotEmpty()) {
            val remoteList = remote.fetchTracks(missing)
            Log.d(TAG, "remote=${remoteList.size}")
            updated.addAll(remoteList)
            putTracks(remoteList)

        }
        return updated
    }

    suspend fun getTrackUrl(trackId: String): yTrack.Companion.Mp3LinkResult {
        val track = _tracks.value[trackId]!!
        return remote.fetch(track)
    }

}