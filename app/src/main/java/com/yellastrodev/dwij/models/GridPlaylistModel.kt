package com.yellastrodev.dwij.models

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yellastrodev.dwij.adapters.GridPlaylistAdapter
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.repo.CoverRepository
import com.yellastrodev.dwij.data.repo.PlaylistRepository
import com.yellastrodev.yandexmusiclib.CONSTANTS.Companion.LIKED_ID
import com.yellastrodev.yandexmusiclib.entities.CoverSize
import kotlinx.coroutines.launch

class GridPlaylistModel(
	private val repo: PlaylistRepository,
	private val coverRepo: CoverRepository
): ViewModel() {

	val TAG = "GridPlaylistModel"

	class Factory(
		private val repo: PlaylistRepository,
		private val coverRepo: CoverRepository
	) : ViewModelProvider.Factory {
		@Suppress("UNCHECKED_CAST")
		override fun <T : ViewModel> create(modelClass: Class<T>): T {
			if (modelClass.isAssignableFrom(GridPlaylistModel::class.java)) {
				return GridPlaylistModel(repo, coverRepo) as T
			}
			throw IllegalArgumentException("Unknown ViewModel class")
		}
	}


	val adapter: GridPlaylistAdapter by lazy {
		GridPlaylistAdapter { coverUrl ->
			coverRepo.getCover(coverUrl, CoverSize.`100x100`) // suspend функция
		}
			.apply {
			mScope = viewModelScope
		}
	}

	init {
		viewModelScope.launch {
			repo.playlists.collect {
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
		repo.refreshPlaylists()
	}
}