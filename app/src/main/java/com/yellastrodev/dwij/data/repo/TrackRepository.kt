package com.yellastrodev.dwij.data.repo

import android.util.Log
import com.yellastrodev.dwij.data.dao.dTrackDao
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.onEach
import kotlin.collections.filterNot
import kotlin.collections.map
import kotlin.collections.mapNotNull


class TrackRepository(
    private val remote: TrackRemoteSource,
    private val local: dTrackDao
) {

    val TAG = "TrackRepository"
    private val _tracks = MutableStateFlow<Map<String, dYaTrack>>(emptyMap())
    val tracks: StateFlow<Map<String, dYaTrack>> = _tracks


    init {
//        GlobalScope.launch(Dispatchers.IO) {
//            local.getAll().forEach { track ->
//                _tracks.value = _tracks.value + (track.id to track)
//            }
//        }
    }

    suspend fun refreshTrackLocaly(trackId: String) {
        val track = local.getTrack(trackId)!!
        _tracks.update { current ->
            current + (trackId to track)
        }
    }

    suspend fun refreshTrackListLocaly(trackIds: List<String>) {
        val tracks = local.getTracks(trackIds)
        _tracks.update { current ->
            current + tracks.associateBy { it.id }
        }
    }

    suspend fun getTrack(trackId: String): dYaTrack {
        if (!_tracks.value.containsKey(trackId)) {
            local.getTrack(trackId)?.let {
                if (it.albums.size < 1){
                    Log.d(TAG, "у трека локально нет альбома, скачиваем из удалённого")
                    val remoteTrack = remote.fetchTracks(listOf(trackId))[0]
                    local.insert(remoteTrack)
                    _tracks.update { current ->
                        current + (trackId to remoteTrack)
                    }
                }else
                    _tracks.update { current ->
                        current + (trackId to it)
                    }

            } ?: run {
                val remoteTrack = remote.fetchTracks(listOf(trackId))[0]
                local.insert(remoteTrack)
                refreshTrackLocaly(trackId)
            }
        }
        return _tracks.value[trackId]!!
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
            .onEach { cache ->
                val missing = ids.filterNot { cache.containsKey(it) }
                if (missing.isNotEmpty()) {
                    Log.d(TAG, "tracksFlow: догружаем ${missing.size} трек(ов)")
                    // Запускаем загрузку в фоне
                    GlobalScope.launch(Dispatchers.IO) {
                        loadTracks(missing)
                    }
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
            val remoteList = loadTracks(missing)
            updated.addAll(remoteList)

        }
        return updated
    }

    suspend fun loadTracks(trackIds: List<String>): List<dYaTrack> {
        Log.d(TAG, "loadTracks(size=${trackIds.size})")
        val result = mutableListOf<dYaTrack>()

        // 1. Берём из локальной базы все, что есть
        val localTracks = local.getTracks(trackIds)
        if (localTracks.isNotEmpty()) {
            _tracks.update { cache -> cache + localTracks.associateBy { it.id } }
            result.addAll(localTracks)
            localTracks.forEach {
                if (it.albums.size < 1) {
                    Log.d(TAG, "у трека локально нет альбома, скачиваем из удалённого")
                    val remoteTrack = remote.fetchTracks(listOf(it.id))[0]
                    local.insert(remoteTrack)
                    _tracks.update { current ->
                            current + (it.id to remoteTrack)
                        }
                }
            }
        }

        // 2. Определяем, что ещё нужно догрузить
        val missing = trackIds.filterNot { id -> _tracks.value.containsKey(id) }

        // 2. Догружаем недостающие с удалённого
        if (missing.isNotEmpty()) {
            Log.d(TAG, "loadTracks(): missing=${missing.size}")
            val remoteList = remote.fetchTracks(missing)
            Log.d(TAG, "loadTracks(): remoteList=${remoteList.size}")

            // Кладём в кэш и сохраняем локально
            putTracks(remoteList)

            result.addAll(remoteList)
        }
        return result
    }

    suspend fun getTrackUrl(trackId: String): yTrack.Companion.Mp3LinkResult {
        val track = _tracks.value[trackId]!!
        return remote.fetch(track)
    }


}