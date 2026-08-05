package com.yellastrodev.dwij.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.entities.iPlaylist
import com.yellastrodev.dwij.data.repo.CoverData
import com.yellastrodev.dwij.data.repo.CoverRepository
import com.yellastrodev.dwij.data.repo.PlaylistRepository
import com.yellastrodev.dwij.data.repo.TrackRepository
import com.yellastrodev.yandexmusiclib.entities.CoverSize
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.collections.emptyList

/** Хранит данные Compose-экрана плейлистов и выполняет операции над выбранным списком. */
class GridPlaylistModel(
    private val playlistRepo: PlaylistRepository,
    private val trackRepo: TrackRepository,
    private val coverRepo: CoverRepository
): ViewModel() {
	val initialLoadComplete: StateFlow<Boolean> = playlistRepo.initialLoadComplete




	/** Отсортированные плейлисты для Compose-сетки: лайки первыми, затем новые списки. */
	val playlists: StateFlow<List<dYaPlaylist>> = playlistRepo.playlists
		.map { playlists ->
			playlists.sortedWith(
				compareBy<dYaPlaylist> { it.kind != "liked" }
					.thenByDescending { it.kind.toIntOrNull() ?: Int.MIN_VALUE }
			)
		}
		.stateIn(
			scope = viewModelScope,
			started = SharingStarted.WhileSubscribed(5_000),
			initialValue = emptyList(),
		)

	/** Загружает квадратную обложку плейлиста через общий кеш обложек. */
    suspend fun getCover(
        playlist: iPlaylist,
    ): CoverData? {
        val yandexPlaylist = playlist as? dYaPlaylist
            ?: return null

        return coverRepo.getPlaylistCover(
            playlist = yandexPlaylist,
            size = CoverSize.`200x200`,
        )
    }

	/** Принудительно обновляет Яндекс-плейлисты и возвращает ошибку вызывающему экрану. */
	suspend fun refreshPlaylists(): DataResult<Unit> = playlistRepo.refreshPlaylists()

	/** Создаёт пустой Яндекс-плейлист и публикует его в текущей сетке. */
	suspend fun createPlaylist(
		title: String,
		isPublic: Boolean,
	): DataResult<dYaPlaylist> = playlistRepo.createPlaylist(title, isPublic)

	suspend fun addTrackToPlaylist(
        playlist: iPlaylist,
        trackId: String
	): DataResult<Unit> {
        return if (playlist is dYaPlaylist) {
            playlistRepo.addTrackToPlaylist(playlist, trackId)
		} else {
			DataResult.Failure(
				DataError.InvalidData("Плейлист ${playlist.getdId()} не является Яндекс-плейлистом")
			)
		}
	}

	suspend fun getTrack(trackId: String): DataResult<dYaTrack> {
		return trackRepo.getTrack(trackId)
	}

	suspend fun removeTrackFromPlaylist(
        playlist: iPlaylist,
        track: dYaTrack
	): DataResult<Unit> {
        return if (playlist is dYaPlaylist) {
            playlistRepo.removeTrackFromPlaylist(playlist, track)
		} else {
			DataResult.Failure(
				DataError.InvalidData("Плейлист ${playlist.getdId()} не является Яндекс-плейлистом")
			)
		}
	}
}