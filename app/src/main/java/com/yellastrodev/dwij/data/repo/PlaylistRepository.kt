package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.source.PlaylistCacheSource
//import com.yellastrodev.dwij.data.source.PlaylistLocalSource
import com.yellastrodev.dwij.data.source.PlaylistRemoteSource
import com.yellastrodev.dwij.utils.PlaylistsDiff.Companion.diffPlaylists
import com.yellastrodev.yandexmusiclib.entities.YaPlaylist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistRepository(
//    private val local: PlaylistLocalSource,
    private val remote: PlaylistRemoteSource,
    private val cache: PlaylistCacheSource,
    private val scope: CoroutineScope
) {

    private val _playlistMap = MutableStateFlow<Map<String, YaPlaylist>>(emptyMap())
    val playlists: StateFlow<List<YaPlaylist>> =
        _playlistMap.map { it.values.toList() }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            val cached = cache.getAll()
            if (cached.isNotEmpty()) _playlistMap.value = cached.associateBy { it.playlistUuid }

            refreshPlaylists()
        }
    }

    suspend fun refreshPlaylists(){
        val remoteData = remote.fetchAll()
        val dif = diffPlaylists(_playlistMap.value, remoteData)
        if (dif.isNotEmpty()) {

            _playlistMap.value = remoteData.associateBy { it.playlistUuid }

            dif.forEachNew {
                var playlist = _playlistMap.value[it]!!
                playlist = remote.fetch(playlist.kind)
                cache.put(playlist)
                _playlistMap.value = _playlistMap.value + (playlist.playlistUuid to playlist)
            }
            dif.removed.forEach {
                cache.remove(it)
                _playlistMap.value = _playlistMap.value - it
            }
        }
    }
}