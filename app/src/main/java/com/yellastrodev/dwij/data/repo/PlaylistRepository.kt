package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.source.PlaylistCacheSource
//import com.yellastrodev.dwij.data.source.PlaylistLocalSource
import com.yellastrodev.dwij.data.source.PlaylistRemoteSource
import com.yellastrodev.yandexmusiclib.entities.YaPlaylist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistRepository(
//    private val local: PlaylistLocalSource,
    private val remote: PlaylistRemoteSource,
    private val cache: PlaylistCacheSource,
    private val scope: CoroutineScope
) {

    private val _playlistMap = MutableStateFlow<Map<String, YaPlaylist>>(emptyMap())
    val _playlists: StateFlow<List<YaPlaylist>> = _playlistMap.map { it.values.toList() }
        .stateIn(scope, SharingStarted.Lazily, emptyList())
//    private val _playlists = MutableStateFlow<List<YaPlaylist>>(emptyList())

    init {
        scope.launch {
            val cached = cache.getAll()
            if (cached.isNotEmpty()) _playlistMap.value = cached.associateBy { it.playlistUuid }

            val remoteData = remote.fetchAll()
            if (remoteData != _playlists.value) {
                remoteData.forEach { cache.put(it) }
                _playlistMap.value = cached.associateBy { it.playlistUuid }
            }

//            preloadDetails(_playlists.value)
        }
    }

    fun observePlaylists(): StateFlow<List<YaPlaylist>> = _playlists

    fun observePlaylist(id: String): Flow<YaPlaylist> =
        _playlists.mapNotNull { list -> list.find { it.playlistUuid == id } }



    fun observePlaylist(id: Int, kind: String): Flow<YaPlaylist> = flow {
        // отдать кеш, если есть
        cache.get(id)?.let { emit(it) }

        // спросить API и сравнить ревизию
        val remotePl = remote.fetch(kind)
        val cached = cache.get(id)
        if (cached == null || remotePl.revision != cached.revision) {
            cache.put(remotePl)
            emit(remotePl)
        }
    }
    suspend fun getPlaylist(id: Int, kind: String): YaPlaylist {
        // 1. Проверяем кэш
        cache.get(id)?.let { return it }

//        // 2. Проверяем локальную базу (Room)
//        local.get(id)?.let { playlist ->
//            cache.put(playlist)
//            return playlist
//        }

        // 3. Берём из API
        val playlist = remote.fetch(kind)
//        local.save(playlist)
        cache.put(playlist)
        return playlist
    }

    suspend fun getAll(): List<YaPlaylist> {
        val cached = cache.getAll()
        if (cached.isNotEmpty()) return cached

//        val localData = local.getAll()
//        if (localData.isNotEmpty()) {
//            localData.forEach { cache.put(it) }
//            return localData
//        }

        val remoteData = remote.fetchAll()
//        local.saveAll(remoteData)
        remoteData.forEach { cache.put(it) }
        return remoteData
    }

    private fun preloadDetails(playlists: List<YaPlaylist>) {
//        playlists.forEach { playlist ->
//            scope.launch {
//                val full = getPlaylist(playlist.uid, playlist.kind.toString())
//                cache.put(full)
//                // пушим обновлённый список
//                _playlists.value = cache.getAll()
//                // обновляем только конкретный элемент
//                _playlistMap.update { current ->
//                    current + (full.uid to full)
//                }
//            }
//        }
    }

    private suspend fun refreshAll() {
        val remoteData = remote.fetchAll()
        val updated = mutableListOf<YaPlaylist>()

        for (remotePlaylist in remoteData) {
            val cached = cache.get(remotePlaylist.uid)
            if (cached == null || remotePlaylist.revision > cached.revision) {
                cache.put(remotePlaylist)
                updated.add(remotePlaylist)
            }
        }

//        if (updated.isNotEmpty()) {
//            _playlists.value = cache.getAll()
//        }
    }
}