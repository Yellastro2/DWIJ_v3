package com.yellastrodev.dwij.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yellastrodev.dwij.adapters.GridPlaylistAdapter
import com.yellastrodev.dwij.data.repo.AlbumCoverRepository
import com.yellastrodev.dwij.data.repo.PlaylistRepository
import com.yellastrodev.yandexmusiclib.entities.CoverSize
import kotlinx.coroutines.launch

class GridPlaylistModel(
	private val repo: PlaylistRepository,
	private val coverRepo: AlbumCoverRepository
): ViewModel() {

	class Factory(
		private val repo: PlaylistRepository,
		private val coverRepo: AlbumCoverRepository
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
					adapter.setList(ArrayList(it))
				}
			}
		}
	}

	suspend fun refreshPlaylists() {
		repo.refreshPlaylists()
	}

//	fun getAdapter(fTrack: Any? = null): GridPlaylistAdapter {
//		if(mAdapter == null) mAdapter = GridPlaylistAdapter(fTrack)
//
//		val fAdapter = mAdapter!!
//
//		if (fAdapter.mTrack != fTrack && fTrack!=null) fAdapter.mTrack = fTrack
//
//		fAdapter.mScope = viewModelScope
//
//
//		return fAdapter
//	}
}