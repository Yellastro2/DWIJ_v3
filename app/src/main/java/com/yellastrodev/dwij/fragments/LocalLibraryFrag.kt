package com.yellastrodev.dwij.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.yellastrodev.dwij.LocalLibraryScreen
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntity
import com.yellastrodev.dwij.data.entities.LocalTrackEntity
import com.yellastrodev.dwij.data.entities.LocalTracklist
import com.yellastrodev.dwij.yApplication
import com.yellastrodev.dwij.work.LocalLibrarySyncWorker
import kotlinx.coroutines.launch

/** Показывает быстрое Room-представление локальных треков и плейлистов. */
class LocalLibraryFrag : Fragment() {
    private val app: yApplication
        get() = requireActivity().application as yApplication

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val mode = arguments?.getString(ARG_MODE) ?: MODE_ALL_TRACKS
            val playlistId = arguments?.getString(ARG_PLAYLIST_ID)
            val repository = app.localMusicRepository
            when {
                mode == MODE_PLAYLISTS -> {
                    val playlists by repository.playlists.collectAsState(initial = emptyList())
                    LocalLibraryScreen(
                        title = getString(R.string.local_playlists_title),
                        playlists = playlists,
                        tracks = null,
                        onPlaylistClick = ::openPlaylist,
                        onTrackClick = { _, _ -> },
                        modifier = Modifier.fillMaxSize().background(Color(0xFF101116)),
                    )
                }
                mode == MODE_PLAYLIST && playlistId != null -> {
                    val playlist by repository.playlist(playlistId).collectAsState(initial = null)
                    val tracks by repository.playlistTracks(playlistId)
                        .collectAsState(initial = emptyList())
                    LocalLibraryScreen(
                        title = playlist?.name ?: getString(R.string.local_playlist_title),
                        playlists = null,
                        tracks = tracks,
                        onPlaylistClick = {},
                        onTrackClick = { index, _ ->
                            playTracks(
                                tracks = tracks,
                                index = index,
                                tracklist = LocalTracklist(
                                    id = playlistId,
                                    name = playlist?.name ?: getString(R.string.local_playlist_title),
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxSize().background(Color(0xFF101116)),
                    )
                }
                else -> {
                    val tracks by repository.tracks.collectAsState(initial = emptyList())
                    LocalLibraryScreen(
                        title = getString(R.string.local_all_tracks_title),
                        playlists = null,
                        tracks = tracks,
                        onPlaylistClick = {},
                        onTrackClick = { index, _ ->
                            playTracks(
                                tracks = tracks,
                                index = index,
                                tracklist = LocalTracklist(
                                    id = "local:all",
                                    name = getString(R.string.local_all_tracks_title),
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxSize().background(Color(0xFF101116)),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        LocalLibrarySyncWorker.enqueueImmediate(requireContext().applicationContext)
    }

    private fun openPlaylist(playlist: LocalPlaylistEntity) {
        findNavController().navigate(
            R.id.localLibraryFrag,
            Bundle().apply {
                putString(ARG_MODE, MODE_PLAYLIST)
                putString(ARG_PLAYLIST_ID, playlist.playlistId)
            },
        )
    }

    private fun playTracks(
        tracks: List<LocalTrackEntity>,
        index: Int,
        tracklist: LocalTracklist,
    ) {
        if (index !in tracks.indices) return
        viewLifecycleOwner.lifecycleScope.launch {
            Log.d(
                TAG,
                "[playTracks] Запускаем локальную очередь: index=$index, size=${tracks.size}",
            )
            app.playerRepo.playLocalQueue(tracks, index, tracklist)
            findNavController().navigate(R.id.bigPlayerFrag)
        }
    }

    companion object {
        const val ARG_MODE = "local_library_mode"
        const val ARG_PLAYLIST_ID = "local_playlist_id"
        const val MODE_PLAYLISTS = "playlists"
        const val MODE_ALL_TRACKS = "all_tracks"
        const val MODE_PLAYLIST = "playlist"
        private const val TAG = "LocalLibraryFrag"
    }
}
