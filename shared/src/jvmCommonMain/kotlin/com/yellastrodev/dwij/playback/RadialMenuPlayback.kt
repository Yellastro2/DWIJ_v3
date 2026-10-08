package com.yellastrodev.dwij.playback

import com.yellastrodev.dwij.RadialMenuCollection
import com.yellastrodev.dwij.RadialMenuTarget
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.toDataError
import com.yellastrodev.dwij.data.entities.LocalTracklist
import com.yellastrodev.dwij.data.entities.MusicSource
import com.yellastrodev.dwij.data.entities.dSimpleTracklist
import com.yellastrodev.dwij.data.repo.VK_ALL_TRACKS
import com.yellastrodev.dwij.data.repo.VK_MY_TRACKS
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.yamusicsdk.network.YamResult
import kotlinx.coroutines.flow.first

/**
 * Запускает коллекцию, сохранённый плейлист или новую ЯМ-волну по исходному seed.
 * Каждый вызов читает актуальное содержимое. Разрешения проверяет HomeRoute.
 */
internal suspend fun DwijComponent.playRadialMenuTarget(target: RadialMenuTarget): DataResult<Unit> {
    if (waveRepository.isLoading.value || dailyPlaylistPlayback.isStarting || mixedRecommendationPlayback.loading.value) {
        return DataResult.Failure(DataError.InvalidData("Дождитесь завершения текущего запуска"))
    }
    if (target is RadialMenuTarget.YandexWave) {
        return if (waveRepository.requestStationWave(target.seed, target.title ?: "Волна")) DataResult.Success(Unit)
        else DataResult.Failure(DataError.InvalidData("Не удалось начать загрузку волны"))
    }
    if (target is RadialMenuTarget.Playlist) {
        return when (target.source) {
            MusicSource.LOCAL -> {
                val sync = localMusicRepository.synchronize(force = false)
                if (sync is DataResult.Failure) return sync
                val playlist = localMusicRepository.playlist(target.key).first()
                    ?: return DataResult.Failure(DataError.NotFound("playlist", target.key))
                val songs = localMusicRepository.playlistSongs(target.key).first()
                if (songs.isEmpty()) return DataResult.Failure(DataError.InvalidData("В плейлисте пока нет треков"))
                waveRepository.stopObserving()
                playerRepo.playQueue(songs, 0, LocalTracklist(target.key, playlist.name))
                DataResult.Success(Unit)
            }
            MusicSource.YANDEX -> playRadialYandexPlaylist(target.key)
            MusicSource.VK -> playRadialVkPlaylist(target.key)
        }
    }
    target as RadialMenuTarget.Collection
    return when (target.kind) {
        RadialMenuCollection.LOCAL_TRACKS -> {
            val sync = localMusicRepository.synchronize(force = false)
            if (sync is DataResult.Failure) return sync
            val songs = localMusicRepository.songs.first()
            if (songs.isEmpty()) return DataResult.Failure(DataError.InvalidData("Локальные треки не найдены"))
            waveRepository.stopObserving()
            playerRepo.playQueue(songs, 0, LocalTracklist("local:all", "Локальные треки"))
            DataResult.Success(Unit)
        }
        RadialMenuCollection.YANDEX_TRACKS -> {
            val refresh = playlistRepository.refreshPlaylists()
            if (refresh is DataResult.Failure) return refresh
            val ids = playlistRepository.playlists.value.flatMap { it.tracks }.map { it.trackId }.distinct()
            val tracks = when (val result = trackRepository.getTracks(ids)) {
                is DataResult.Failure -> return result
                is DataResult.Success -> result.value
            }
            val songs = songRepository.songsForYandexTracks(tracks)
            if (songs.isEmpty()) return DataResult.Failure(DataError.InvalidData("В фонотеке ЯМ пока нет треков"))
            waveRepository.stopObserving()
            playerRepo.playQueue(songs, 0, dSimpleTracklist())
            DataResult.Success(Unit)
        }
        RadialMenuCollection.YANDEX_DAILY -> {
            when (val result = dailyPlaylistPlayback.load()) {
                is YamResult.Failure -> DataResult.Failure(result.error.toDataError())
                is YamResult.Success -> {
                    val details = result.value
                        ?: return DataResult.Failure(DataError.InvalidData("Плейлист дня пока не готов"))
                    if (dailyPlaylistPlayback.play(details)) DataResult.Success(Unit)
                    else DataResult.Failure(DataError.InvalidData("Плейлист дня пуст или другой запуск ещё загружается"))
                }
            }
        }
        RadialMenuCollection.YANDEX_LIKED -> {
            playlistRepository.initialLoadComplete.first { it }
            if (playlistRepository.getLikeList() == null) {
                val refresh = playlistRepository.refreshPlaylists()
                if (refresh is DataResult.Failure) return refresh
            }
            val liked = playlistRepository.getLikeList()
                ?: return DataResult.Failure(DataError.InvalidData("Список любимых ЯМ не найден"))
            playRadialYandexPlaylist(liked.playlistUuid)
        }
        RadialMenuCollection.VK_MY_TRACKS,
        RadialMenuCollection.VK_ALL_TRACKS,
        RadialMenuCollection.VK_RECOMMENDATIONS -> {
            val id = when (target.kind) {
                RadialMenuCollection.VK_MY_TRACKS -> VK_MY_TRACKS
                RadialMenuCollection.VK_ALL_TRACKS -> VK_ALL_TRACKS
                else -> "vkrec:recommendations"
            }
            playRadialVkPlaylist(id)
        }
        RadialMenuCollection.MIXED_RECOMMENDATIONS -> mixedRecommendationPlayback.play("Подборка")
    }
}

/** Обновляет состав существующего ЯМ-плейлиста и собирает общие Song перед запуском. */
private suspend fun DwijComponent.playRadialYandexPlaylist(key: String): DataResult<Unit> {
    playlistRepository.initialLoadComplete.first { it }
    if (playlistRepository.playlists.value.none { it.playlistUuid == key }) {
        val refresh = playlistRepository.refreshPlaylists()
        if (refresh is DataResult.Failure) return refresh
    }
    val refresh = playlistRepository.refreshPlaylist(key)
    if (refresh is DataResult.Failure) return refresh
    val current = playlistRepository.playlistFlow(key).first()
    val tracks = when (val result = trackRepository.getTracks(current.tracks.map { it.trackId })) {
        is DataResult.Failure -> return result
        is DataResult.Success -> result.value
    }
    val songs = songRepository.songsForYandexTracks(tracks)
    if (songs.isEmpty()) return DataResult.Failure(DataError.InvalidData("В списке ЯМ пока нет треков"))
    waveRepository.stopObserving()
    playerRepo.playQueue(songs, 0, current)
    return DataResult.Success(Unit)
}

/** Загружает VK-плейлист либо виртуальную подборку, проверяя смену аккаунта до запуска. */
private suspend fun DwijComponent.playRadialVkPlaylist(key: String): DataResult<Unit> {
    if (!vkMusicRepository.authorized.value) return DataResult.Failure(DataError.Unauthorized)
    val session = vkMusicRepository.sessionRevision.value
    val content = when (val result = vkMusicRepository.getPlaylist(key, preferLocal = false)) {
        is DataResult.Failure -> return result
        is DataResult.Success -> result.value
    }
    if (session != vkMusicRepository.sessionRevision.value) return DataResult.Failure(DataError.Unauthorized)
    if (content.tracks.isEmpty()) return DataResult.Failure(DataError.InvalidData("В списке VK пока нет треков"))
    waveRepository.stopObserving()
    return vkMusicRepository.playPlaylist(content.playlist, content.tracks, 0, playerRepo)
}
