package com.yellastrodev.dwij.data.repo

import android.util.Log
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.dao.dPlaylistDao
import com.yellastrodev.dwij.data.entities.dYaLikeTracklist
import com.yellastrodev.dwij.data.entities.dYaLikeTracklist.Companion.KIND_LIKED
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.source.PlaylistCacheSource
import com.yellastrodev.dwij.data.source.PlaylistRemoteSource
import com.yellastrodev.dwij.data.source.PlaylistSnapshot
import com.yellastrodev.dwij.utils.PlaylistsDiff.Companion.diffPlaylists
import kotlinx.coroutines.CancellationException
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
    private val local: dPlaylistDao,
    private val remote: PlaylistRemoteSource,
    private val cache: PlaylistCacheSource,
    private val scope: CoroutineScope,
    private val trackRepo: TrackRepository
) {

    private val _playlistMap = MutableStateFlow<Map<String, dYaPlaylist>>(emptyMap())
    val playlists: StateFlow<List<dYaPlaylist>> =
        _playlistMap.map { it.values.toList() }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            val cached = cache.getAll()
            if (cached.isNotEmpty()) {
                Log.d(TAG, "[init] Используем кеш плейлистов, размер=${cached.size}")
                _playlistMap.value = cached.associateBy { it.playlistUuid }
            } else {
                Log.d(TAG, "[init] Загружаем плейлисты из локальной БД")
                val localData = local.getAlldPlaylists()
                if (localData.isNotEmpty()) {
                    cache.putAll(localData)
                    _playlistMap.value = localData.associateBy { it.playlistUuid }
                }
                Log.d(TAG, "[init] Из локальной БД загружено ${localData.size} плейлистов")
            }

            try {
                refreshPlaylists()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "[init] Ошибка обновления плейлистов", error)
            }
        }
    }

    suspend fun refreshPlaylists(): DataResult<Unit> {
        Log.d(TAG, "[refreshPlaylists] Загружаем плейлисты с сервера")
        val remoteData = when (val result = remote.fetchAll()) {
            is DataResult.Success -> ArrayList(result.value)
            is DataResult.Failure -> {
                Log.e(TAG, "[refreshPlaylists] Список не загружен: ${result.error}")
                return result
            }
        }
        when (val likeResult = remote.fetchLikelist()) {
            is DataResult.Success -> remoteData.add(likeResult.value)
            is DataResult.Failure -> {
                getLikeList()?.let(remoteData::add)
                Log.w(TAG, "[refreshPlaylists] Лайки не загружены: ${likeResult.error}")
            }
        }

        val diff = diffPlaylists(_playlistMap.value, remoteData)
        if (diff.isNotEmpty()) {
            Log.d(
                TAG,
                "[refreshPlaylists] Изменения: added=${diff.added.size}, " +
                    "changed=${diff.changed.size}, removed=${diff.removed.size}"
            )
            for (uuid in diff.added + diff.changed) {
                var playlist = remoteData.first { it.playlistUuid == uuid }
                var snapshot: PlaylistSnapshot? = null
                if (playlist.kind != KIND_LIKED) {
                    val kind = playlist.kind.toIntOrNull()
                        ?: return DataResult.Failure(
                            DataError.InvalidData("Некорректный kind=${playlist.kind}")
                        )
                    when (val result = remote.fetch(kind)) {
                        is DataResult.Success -> {
                            snapshot = result.value
                            playlist = result.value.playlist
                        }
                        is DataResult.Failure -> return result
                    }
                } else {
                    when (val result = trackRepo.getTracks(
                        playlist.tracks.map { it.trackId }
                    )) {
                        is DataResult.Success -> Unit
                        is DataResult.Failure -> Log.w(
                            TAG,
                            "[refreshPlaylists] Треки лайков не загружены: ${result.error}"
                        )
                    }
                }
                val saveResult = snapshot?.let { saveSnapshot(it) }
                    ?: savePlaylist(playlist)
                when (saveResult) {
                    is DataResult.Success -> Unit
                    is DataResult.Failure -> return saveResult
                }
            }
            diff.removed.forEach { uuid ->
                when (val deleteResult = deletePlaylist(uuid)) {
                    is DataResult.Success -> Unit
                    is DataResult.Failure -> return deleteResult
                }
            }
        }
        return DataResult.Success(Unit)
    }

    fun getPlaylistsByKeys(keys: List<String>): Flow<List<dYaPlaylist>> =
        _playlistMap.map { map -> keys.mapNotNull(map::get) }

    fun playlistFlow(playlistUuid: String): Flow<dYaPlaylist> =
        _playlistMap
            .map { it[playlistUuid] }
            .filterNotNull()
            .distinctUntilChanged()

    /** Создаёт Яндекс-плейлист, сохраняет его локально и сразу публикует в списке экрана. */
    suspend fun createPlaylist(
        title: String,
        isPublic: Boolean,
    ): DataResult<dYaPlaylist> {
        val normalizedTitle = title.trim()
        if (normalizedTitle.isBlank()) {
            return DataResult.Failure(DataError.InvalidData("Название не должно быть пустым"))
        }
        return when (val result = remote.createPlaylist(normalizedTitle, isPublic)) {
            is DataResult.Success -> {
                when (val saveResult = savePlaylist(result.value)) {
                    is DataResult.Success -> {
                        Log.d(
                            TAG,
                            "[createPlaylist] Создан плейлист '${result.value.title}'",
                        )
                        result
                    }
                    is DataResult.Failure -> saveResult
                }
            }
            is DataResult.Failure -> result
        }
    }

    suspend fun refreshPlaylist(plUuid: String): DataResult<Unit> {
        val playlist = _playlistMap.value[plUuid]
            ?: return DataResult.Failure(DataError.NotFound("playlist", plUuid))
        if (playlist.kind == KIND_LIKED) {
            return refreshLikedPlaylist()
        }
        val kind = playlist.kind.toIntOrNull()
            ?: return DataResult.Failure(
                DataError.InvalidData("Некорректный kind=${playlist.kind}")
            )

        return when (val result = remote.fetch(kind)) {
            is DataResult.Success -> {
                val snapshot = result.value
                saveSnapshot(snapshot)
            }
            is DataResult.Failure -> result
        }
    }

    suspend fun addTrackToPlaylist(
        playlist: dYaPlaylist,
        trackId: String
    ): DataResult<Unit> {
        val track = when (val result = trackRepo.getTrack(trackId)) {
            is DataResult.Success -> result.value
            is DataResult.Failure -> return result
        }
        return when (val result = remote.addTrackToPlaylist(playlist, track)) {
            is DataResult.Success -> {
                when (val saveResult = savePlaylist(result.value)) {
                    is DataResult.Success -> Unit
                    is DataResult.Failure -> return saveResult
                }
                trackRepo.refreshTrackLocaly(trackId)
                Log.d(TAG, "[addTrackToPlaylist] Трек $trackId добавлен")
                DataResult.Success(Unit)
            }
            is DataResult.Failure -> result
        }
    }

    suspend fun removeTrackFromPlaylist(
        playlist: dYaPlaylist,
        track: dYaTrack
    ): DataResult<Unit> {
        val relation = playlist.tracks.firstOrNull { it.trackId == track.id }
            ?: return DataResult.Failure(
                DataError.NotFound("playlistTrack", "${playlist.playlistUuid}:${track.id}")
            )
        return when (
            val result = remote.removeTrackFromPlaylist(
                playlist = playlist,
                trackNumber = relation.position
            )
        ) {
            is DataResult.Success -> {
                when (val saveResult = savePlaylist(result.value)) {
                    is DataResult.Success -> Unit
                    is DataResult.Failure -> return saveResult
                }
                trackRepo.refreshTrackLocaly(track.id)
                Log.d(
                    TAG,
                    "[removeTrackFromPlaylist] Трек ${track.title} удалён " +
                        "из ${playlist.title}"
                )
                DataResult.Success(Unit)
            }
            is DataResult.Failure -> result
        }
    }

    fun getLikeList(): dYaPlaylist? =
        _playlistMap.value.values.find { it.kind == KIND_LIKED }

    suspend fun setTrackLiked(trackId: String, liked: Boolean): DataResult<Unit> {
        Log.d(TAG, "[setTrackLiked] trackId=$trackId, liked=$liked")
        val result = remote.setTrackLiked(trackId, liked)
        if (result is DataResult.Success) {
            val likeList = getLikeList()
            if (likeList == null) {
                refreshLikedPlaylist()
            } else {
                refreshPlaylist(likeList.playlistUuid)
            }
        }
        return result
    }

    private suspend fun refreshLikedPlaylist(): DataResult<Unit> {
        return try {
            when (val result = remote.fetchLikelist()) {
                is DataResult.Success -> {
                    val playlist = result.value
                    when (val tracksResult = trackRepo.getTracks(
                        playlist.tracks.map { it.trackId }
                    )) {
                        is DataResult.Success -> Unit
                        is DataResult.Failure -> return tracksResult
                    }
                    when (val saveResult = savePlaylist(playlist)) {
                        is DataResult.Success -> Unit
                        is DataResult.Failure -> return saveResult
                    }
                    Log.d(TAG, "[refreshLikedPlaylist] Список лайков обновлён")
                    DataResult.Success(Unit)
                }
                is DataResult.Failure -> {
                    Log.e(TAG, "[refreshLikedPlaylist] Ошибка: ${result.error}")
                    result
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "[refreshLikedPlaylist] Ошибка локального сохранения", error)
            DataResult.Failure(DataError.Storage(error))
        }
    }

    private suspend fun saveSnapshot(snapshot: PlaylistSnapshot): DataResult<Unit> {
        return try {
            trackRepo.putTracks(snapshot.tracks)
            savePlaylist(snapshot.playlist)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DataResult.Failure(DataError.Storage(error))
        }
    }

    private suspend fun savePlaylist(playlist: dYaPlaylist): DataResult<Unit> {
        return try {
            cache.put(playlist)
            local.insert(playlist)
            _playlistMap.value = _playlistMap.value +
                (playlist.playlistUuid to playlist)
            DataResult.Success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DataResult.Failure(DataError.Storage(error))
        }
    }

    private suspend fun deletePlaylist(uuid: String): DataResult<Unit> {
        return try {
            cache.remove(uuid)
            local.delete(uuid)
            _playlistMap.value = _playlistMap.value - uuid
            DataResult.Success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DataResult.Failure(DataError.Storage(error))
        }
    }

    private companion object {
        const val TAG = "PlaylistRepository"
    }
}
