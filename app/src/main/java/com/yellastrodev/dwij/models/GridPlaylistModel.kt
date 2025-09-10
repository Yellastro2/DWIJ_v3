package com.yellastrodev.dwij.models

import androidx.lifecycle.ViewModel
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


	val adapter: GridPlaylistAdapter by lazy {
		GridPlaylistAdapter { coverUrl ->
			coverRepo.getCover(coverUrl, CoverSize.`100x100`) // suspend функция
		}
			.apply {
			mScope = viewModelScope
		}
	}

	init {
		loadPlaylists()
	}

	private fun loadPlaylists() {
		viewModelScope.launch {
//			val playlists = repo.getAll() // синхронно или suspend
			repo._playlists.collect {
				adapter.setList(ArrayList(it))
			}
//			adapter.setList(ArrayList(playlists))
//			playlists.forEach { playlist ->
//				// добавляем в адаптер, он сам через DiffUtil обновится
//				adapter.addItem(playlist)
//
//				// фоновая загрузка треков для ускорения открытия
//				launch {
//					val tracks = repo.getTracksForPlaylist(playlist.id)
//					playlist.tracks = tracks
//				}
//			}
		}
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