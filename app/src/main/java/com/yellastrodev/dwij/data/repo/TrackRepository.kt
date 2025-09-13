package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.source.TrackLocalSource
import com.yellastrodev.dwij.data.source.TrackRemoteSource
import com.yellastrodev.dwij.entities.dPlaylistTrack
import com.yellastrodev.dwij.entities.dYaTrack
import com.yellastrodev.yandexmusiclib.entities.TrackShort
import com.yellastrodev.yandexmusiclib.entities.YaTrack
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
        val updated = _tracks.value.toMutableMap()
        trackList.forEach { track ->
            if (!updated.containsKey(track.id)) {
                updated[track.id] = track
            }
        }
        _tracks.value = updated

        GlobalScope.launch(Dispatchers.IO) {
            local.saveAll(trackList)
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

    suspend fun getTrack(track: dYaTrack): yTrack.Companion.Mp3LinkResult {
        return remote.fetch(track)
    }

    suspend fun getTrackUrl(trackId: String): yTrack.Companion.Mp3LinkResult {
        val track = _tracks.value[trackId]!!
        return remote.fetch(track)
    }

}