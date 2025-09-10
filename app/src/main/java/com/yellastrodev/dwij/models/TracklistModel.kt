package com.yellastrodev.dwij.models

import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yellastrodev.dwij.adapters.TrackListAdapter
import com.yellastrodev.dwij.data.repo.AlbumCoverRepository
import com.yellastrodev.dwij.data.repo.PlaylistRepository
import com.yellastrodev.dwij.data.repo.TrackRepository
import com.yellastrodev.yandexmusiclib.entities.CoverSize
import com.yellastrodev.yandexmusiclib.entities.YaPlaylist
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class TracklistModel(
    private val repo: PlaylistRepository,
    val coverRepo: AlbumCoverRepository,
    private val trackRepo: TrackRepository
): ViewModel() {

    class Factory(
        private val repo: PlaylistRepository,
        private val coverRepo: AlbumCoverRepository,
        private val trackRepo: TrackRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(TracklistModel::class.java)) {
                return TracklistModel(repo, coverRepo, trackRepo) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    private val _playlist = MutableStateFlow<YaPlaylist?>(null)
    val playlist: StateFlow<YaPlaylist?> = _playlist

    val adapter: TrackListAdapter by lazy {
        TrackListAdapter { coverUrl ->
            coverRepo.getCover(coverUrl, CoverSize.`100x100`) // suspend функция
        }
            .apply {
                mScope = viewModelScope
            }
    }


    suspend fun setType(type: String, value: String) {
        if (type == "playlist") {
            repo.playlistFlow(value).collect { playlist ->
                _playlist.value = playlist

                adapter.setList(
                    ArrayList(trackRepo.getTracksNotNull(playlist.tracks))
                )
            }

        }
    }


}