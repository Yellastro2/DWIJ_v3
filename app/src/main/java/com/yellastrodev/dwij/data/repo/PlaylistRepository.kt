package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.source.PlaylistCacheSource
//import com.yellastrodev.dwij.data.source.PlaylistLocalSource
import com.yellastrodev.dwij.data.source.PlaylistRemoteSource
import com.yellastrodev.dwij.utils.PlaylistsDiff.Companion.diffPlaylists
import com.yellastrodev.yandexmusiclib.YamApiClient.PlaylistResult
import com.yellastrodev.yandexmusiclib.entities.YaPlaylist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistRepository(
//  TODO  private val local: PlaylistLocalSource,
    private val remote: PlaylistRemoteSource,
    private val cache: PlaylistCacheSource,
    private val scope: CoroutineScope,
    private val trackRepo: TrackRepository
) {

    /**
     * Мапа плейлистов, кэшированный в памяти, ключи это .playlistUuid
     */
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
                val plResult = remote.fetch(playlist.kind)
                if (plResult is PlaylistResult.Success) {
                    playlist = plResult.YaPlaylist
                    trackRepo.putTracks(plResult.trackList)
                }
                cache.put(playlist)
                _playlistMap.value = _playlistMap.value + (playlist.playlistUuid to playlist)
            }
            dif.removed.forEach {
                cache.remove(it)
                _playlistMap.value = _playlistMap.value - it
            }
        }
    }

    fun playlistFlow(playlistUuid: String): Flow<YaPlaylist> =
        _playlistMap
            .map { it[playlistUuid] }      // достаём элемент по ключу
            .filterNotNull()               // пропускаем null
            .distinctUntilChanged()        // опционально, чтобы не пушить одинаковое

    suspend fun refreshPlaylist(plUuid: String) {
        val playlist = _playlistMap.value[plUuid]!!
        val plResult = remote.fetch(playlist.kind)
        if (plResult is PlaylistResult.Success) {
            if (playlist.revision != plResult.YaPlaylist.revision){
                cache.put(plResult.YaPlaylist)
                _playlistMap.value = _playlistMap.value + (plUuid to plResult.YaPlaylist)
                trackRepo.putTracks(plResult.trackList)
            }
        }
    }
}