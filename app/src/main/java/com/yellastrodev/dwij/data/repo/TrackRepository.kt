package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.source.TrackRemoteSource
import com.yellastrodev.yandexmusiclib.entities.TrackShort
import com.yellastrodev.yandexmusiclib.entities.YaTrack
import com.yellastrodev.yandexmusiclib.kot_utils.yTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class TrackRepository(
    private val remote: TrackRemoteSource
) {
    private val _tracks = MutableStateFlow<Map<String, YaTrack>>(emptyMap())
    val tracks: StateFlow<Map<String, YaTrack>> = _tracks

    fun putTracks(trackList: List<YaTrack>) {
        val updated = _tracks.value.toMutableMap()
        trackList.forEach { track ->
            if (!updated.containsKey(track.id)) {
                updated[track.id] = track
            }
        }
        _tracks.value = updated
    }

    fun tracksFlow(shorts: List<TrackShort>): Flow<List<YaTrack>> =
        _tracks
            .map { cache ->
                shorts.mapNotNull { cache[it.id] }
            }
            .distinctUntilChanged()

    suspend fun getTrack(track: YaTrack): yTrack.Companion.Mp3LinkResult {
        return remote.fetch(track)
    }

    suspend fun getTrackUrl(trackId: String): yTrack.Companion.Mp3LinkResult {
        val track = _tracks.value[trackId]!!
        return remote.fetch(track)
    }

}