package com.yellastrodev.dwij.data.repo

import com.yellastrodev.yandexmusiclib.entities.TrackShort
import com.yellastrodev.yandexmusiclib.entities.YaTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class TrackRepository {
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

//    fun getTrack(id: String) = _tracks.value[id]

    fun trackFlow(id: String): Flow<YaTrack?> =
        _tracks.map { it[id] }.distinctUntilChanged()

    // возвращает треки по списку TrackShort, если нет в кеше — null
    fun getTracks(shorts: List<TrackShort>): List<YaTrack?> {
        val cache = _tracks.value
        return shorts.map { cache[it.id] }
    }

    // альтернативно — фильтровать только найденные
    fun getTracksNotNull(shorts: List<TrackShort>): List<YaTrack> =
        shorts.mapNotNull { _tracks.value[it.id] }

}