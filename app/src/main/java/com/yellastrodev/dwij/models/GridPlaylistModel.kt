package com.yellastrodev.dwij.models

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yellastrodev.dwij.adapters.GridPlaylistAdapter
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.entities.iPlaylist
import com.yellastrodev.dwij.data.repo.CoverRepository
import com.yellastrodev.dwij.data.repo.PlaylistRepository
import com.yellastrodev.dwij.data.repo.TrackRepository
import com.yellastrodev.yandexmusiclib.entities.CoverSize
import kotlinx.coroutines.launch

class GridPlaylistModel(
	private val playlistRepo: PlaylistRepository,
	private val trackRepo: TrackRepository,
	private val coverRepo: CoverRepository
): ViewModel() {

	val TAG = "GridPlaylistModel"

	class Factory(
		private val repo: PlaylistRepository,
		private val trackRepo: TrackRepository,
		private val coverRepo: CoverRepository
	) : ViewModelProvider.Factory
	{
		@Suppress("UNCHECKED_CAST")
		override fun <T : ViewModel> create(modelClass: Class<T>): T {
			if (modelClass.isAssignableFrom(GridPlaylistModel::class.java)) {
				return GridPlaylistModel(repo, trackRepo, coverRepo) as T
			}
			throw IllegalArgumentException("Unknown ViewModel class")
		}
	}


	val adapter: GridPlaylistAdapter by lazy {
		GridPlaylistAdapter(this) { coverUrl ->
			coverRepo.getCover(coverUrl, CoverSize.`100x100`) // suspend функция
		}
			.apply {
			mScope = viewModelScope
		}
	}

	init {
		viewModelScope.launch {
			playlistRepo.playlists.collect {
				if (it.isNotEmpty()) {

					adapter.setList(ArrayList(it.sortedWith(
						compareBy<dYaPlaylist> { it.kind != "liked" }
							.thenByDescending { it.kind.toIntOrNull() ?: Int.MIN_VALUE }
					)))
					Log.d(TAG, "не пустой список плейлистов подан в адаптер")
				}
			}
		}
	}

	suspend fun refreshPlaylists() {
		playlistRepo.refreshPlaylists()
	}

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
