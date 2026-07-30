package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.source.PlaylistCacheSource
import com.yellastrodev.dwij.data.source.PlaylistLocalSource
//import com.yellastrodev.dwij.data.source.PlaylistLocalSource
import com.yellastrodev.dwij.data.source.PlaylistRemoteSource
import com.yellastrodev.dwij.data.source.dPlaylistResult
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.utils.PlaylistsDiff.Companion.diffPlaylists
import com.yellastrodev.dwij.data.entities.dYaLikeTracklist.Companion.KIND_LIKED
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import android.util.Log
import com.yellastrodev.dwij.data.dao.dPlaylistDao
import com.yellastrodev.dwij.data.entities.dYaLikeTracklist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.yandexmusiclib.likes.LikeActionResult
import com.yellastrodev.yandexmusiclib.network.YamResult

class PlaylistRepository(
    private val local: dPlaylistDao,
    private val remote: PlaylistRemoteSource,
    private val cache: PlaylistCacheSource,
    private val scope: CoroutineScope,
    private val trackRepo: TrackRepository
) {

    val TAG = "PlaylistRepository"

    /**
     * Мапа плейлистов, кэшированный в памяти, ключи это .playlistUuid
     */
    private val _playlistMap = MutableStateFlow<Map<String, dYaPlaylist>>(emptyMap())
    val playlists: StateFlow<List<dYaPlaylist>> =
        _playlistMap.map { it.values.toList() }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {

            val cached = cache.getAll()
            if (cached.isNotEmpty()) {
                Log.d(TAG, "Используем кэш плейлистов, размер: ${cached.size}")
                _playlistMap.value = cached.associateBy { it.playlistUuid }
            }

            else {
                // 1️⃣ Загружаем из локальной БД
                Log.d(TAG, "Загружаем плейлисты из локальной БД")
                val localData = local.getAlldPlaylists()
                Log.d(TAG, "плейлисты загружены, сохраняем в кеш")
                if (localData.isNotEmpty()) {
                    cache.putAll(localData)
                    _playlistMap.value = localData.associateBy { it.playlistUuid }
                }
                Log.d(TAG, "Загружено плейлистов из локальной БД: ${localData.size}")
            }

            try {
                refreshPlaylists()
            }catch (e: Exception){
                Log.e(TAG, "Ошибка обновления плейлистов", e)
            }
        }
    }

//    val trackLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun refreshPlaylists(){
        Log.d(TAG, "Загружаем плейлисты из удалённого сервера")
        val remoteData = when (val result = remote.fetchAll()) {
            is YamResult.Success -> ArrayList(result.value)
            is YamResult.Failure -> {
                Log.e(
                    TAG,
                    "[refreshPlaylists] Список плейлистов не загружен: ${result.error}"
                )
                return
            }
        }
        when (val likeResult = remote.fetchLikelist()) {
            is YamResult.Success -> remoteData.add(likeResult.value)
            is YamResult.Failure -> {
                getLikeList()?.let { remoteData.add(it) }
                Log.w(
                    TAG,
                    "[refreshPlaylists] Список лайков не загружен: ${likeResult.error}"
                )
            }
        }
        val dif = diffPlaylists(_playlistMap.value, remoteData)
        if (dif.isNotEmpty()) {
            Log.d(TAG,"есть изменения в онлайн плейлистов: ${dif.added.size}, ${dif.changed.size}, ${dif.removed.size}")
//            _playlistMap.value = remoteData.associateBy { it.playlistUuid }

            dif.forEachNew { uuid ->
                var playlist = remoteData.find { it.playlistUuid ==  uuid}!!
                if (playlist.kind != KIND_LIKED) {
                    val plResult = remote.fetch(playlist.kind.toInt())
                    if (plResult is dPlaylistResult.Success) {
                        playlist = plResult.YaPlaylist
                        trackRepo.putTracks(plResult.trackList)
                    }
                }else {
                    trackRepo.getTracks(playlist.tracks.map { it.trackId })
                }
                cache.put(playlist)
                local.insert(playlist)
                _playlistMap.value = _playlistMap.value + (playlist.playlistUuid to playlist)
            }
            dif.removed.forEach {
                cache.remove(it)
                local.delete(it)
                _playlistMap.value = _playlistMap.value - it
            }
        }
    }

    fun getPlaylistsByKeys(keys: List<String>): Flow<List<dYaPlaylist>> =
        _playlistMap.map { map ->
            keys.mapNotNull { map[it] }
        }

    fun playlistFlow(playlistUuid: String): Flow<dYaPlaylist> =
        _playlistMap
            .map { it[playlistUuid] }      // достаём элемент по ключу
            .filterNotNull()               // пропускаем null
            .distinctUntilChanged()        // опционально, чтобы не пушить одинаковое

    // TODO . а еще порядок треков
    //  в плейлисте разьебан, а для удаления надо знать точный
    suspend fun refreshPlaylist(plUuid: String) {
        val playlist = _playlistMap.value[plUuid] ?: run {
            Log.w(TAG, "[refreshPlaylist] Плейлист $plUuid отсутствует в памяти")
            return
        }
        if (playlist.kind == KIND_LIKED) {
            refreshLikedPlaylist()
            return
        }

        val plResult = remote.fetch(playlist.kind.toInt())
        if (plResult is dPlaylistResult.Success) {
            if (playlist.revision != plResult.YaPlaylist.revision){
                cache.put(plResult.YaPlaylist)
                local.insert(plResult.YaPlaylist)
                _playlistMap.value = _playlistMap.value + (plUuid to plResult.YaPlaylist)
                trackRepo.putTracks(plResult.trackList)
            }else {
                scope.launch {
                    trackRepo.tracksFlow(plResult.YaPlaylist.tracks).collect { tracks ->
                        if (tracks.size != playlist.tracks.size)
                        {
                            trackRepo.putTracks(plResult.trackList)
                            cache.put(plResult.YaPlaylist)
                            local.insert(plResult.YaPlaylist)
                            _playlistMap.value = _playlistMap.value + (plUuid to plResult.YaPlaylist)
                        }
                    }
                }
            }
        }
    }

    suspend fun addTrackToPlaylist(playlist: dYaPlaylist, trackId: String) {
        val track = trackRepo.getTrack(trackId)
        remote.addTrackToPlaylist(playlist, track)
        refreshPlaylist(playlist.playlistUuid)
        trackRepo.refreshTrackLocaly(trackId)

    }

    suspend fun removeTrackFromPlaylist(playlist: dYaPlaylist, track: dYaTrack) {
        Log.d(TAG, "removeTrackFromPlaylist( playlist: ${playlist.title}, track: ${track.title} )")
        refreshPlaylist(playlist.playlistUuid)
        val playlistedTrack = playlist.tracks.find { it.trackId == track.id }!!
        Log.d(TAG,"removeTrackFromPlaylist, playlistedTrack.position: ${playlistedTrack.position}")
        remote.removeTrackFromPlaylist(playlist, playlist.tracks.indexOf(playlistedTrack))
        refreshPlaylist(playlist.playlistUuid)
        trackRepo.refreshTrackLocaly(track.id)
        Log.d(TAG, "Трек ${track.title} удалён из плейлиста ${playlist.title}")
    }

    fun getLikeList(): dYaPlaylist? {
        return _playlistMap.value.values.find {
            it.kind == dYaLikeTracklist.KIND_LIKED
        }
    }

    suspend fun setTrackLiked(
        trackId: String,
        liked: Boolean
    ): YamResult<LikeActionResult> {
        Log.d(TAG, "[setTrackLiked] trackId=$trackId, liked=$liked")
        val result = remote.setTrackLiked(trackId, liked)
        if (result is YamResult.Success) {
            val likeList = getLikeList()
            if (likeList == null) {
                refreshLikedPlaylist()
            } else {
                refreshPlaylist(likeList.playlistUuid)
            }
        }
        return result
    }

    private suspend fun refreshLikedPlaylist() {
        try {
            when (val result = remote.fetchLikelist()) {
                is YamResult.Success -> {
                    val playlist = result.value
                    trackRepo.getTracks(playlist.tracks.map { it.trackId })
                    cache.put(playlist)
                    local.insert(playlist)
                    _playlistMap.value = _playlistMap.value +
                        (playlist.playlistUuid to playlist)
                    Log.d(TAG, "[refreshLikedPlaylist] Список лайков обновлён")
                }
                is YamResult.Failure -> {
                    Log.e(
                        TAG,
                        "[refreshLikedPlaylist] Не удалось загрузить список лайков: ${result.error}"
                    )
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "[refreshLikedPlaylist] Ошибка обновления списка лайков", error)
        }
    }
}
