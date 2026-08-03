package com.yellastrodev.dwij.navigation

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.yellastrodev.dwij.LocalLibraryScreen
import com.yellastrodev.dwij.LocalPlaylistObjectScreen
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntity
import com.yellastrodev.dwij.data.entities.LocalTracklist
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.work.LocalLibrarySyncWorker
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Compose-route локальных списков, всех треков и объекта локального плейлиста. */
@Composable
fun LocalLibraryRoute(
    navController: NavHostController,
    mode: String,
    playlistId: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val application = context.applicationContext as yApplication
    val repository = application.localMusicRepository
    val isSynchronizing by repository.isSynchronizing.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val allTracksTitle = stringResource(R.string.local_all_tracks_title)

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                LocalLibrarySyncWorker.enqueueImmediate(context.applicationContext)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    suspend fun loadTrackCover(song: Song): ImageBitmap = withContext(Dispatchers.IO) {
        val track = requireNotNull(song.localInstances.firstOrNull()?.track) {
            "У песни ${song.id} отсутствует локальный экземпляр"
        }
        val source = repository.cover(track).first()
        val largestSide = maxOf(source.width, source.height)
        val displayBitmap = if (largestSide > TRACK_COVER_SIZE_PX) {
            val scale = TRACK_COVER_SIZE_PX.toFloat() / largestSide
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            source
        }
        displayBitmap.asImageBitmap()
    }

    fun openPlaylist(playlist: LocalPlaylistEntity) {
        navController.navigate(
            DwijDestination.localLibraryRoute(
                mode = DwijDestination.LOCAL_MODE_PLAYLIST,
                playlistId = playlist.playlistId,
            ),
        )
    }

    fun playTracks(tracks: List<Song>, index: Int, tracklist: LocalTracklist) {
        if (index !in tracks.indices) return
        coroutineScope.launch {
            Log.d(
                TAG,
                "[playTracks] Запускаем локальную очередь: index=$index, size=${tracks.size}",
            )
            application.playerRepo.playQueue(tracks, index, tracklist)
            navController.navigate(DwijDestination.PLAYER)
        }
    }

    when {
        mode == DwijDestination.LOCAL_MODE_PLAYLISTS -> {
            val playlists by repository.playlists.collectAsState(initial = null)
            LocalLibraryScreen(
                title = stringResource(R.string.local_playlists_title),
                playlists = playlists.orEmpty(),
                tracks = null,
                onPlaylistClick = ::openPlaylist,
                onTrackClick = { _, _ -> },
                loadTrackCover = ::loadTrackCover,
                isLoading = playlists == null ||
                    (playlists.isNullOrEmpty() && isSynchronizing),
                modifier = modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .background(LocalLibraryBackground),
            )
        }
        mode == DwijDestination.LOCAL_MODE_PLAYLIST && playlistId != null -> {
            val playlist by repository.playlist(playlistId).collectAsState(initial = null)
            val tracks by repository.playlistSongs(playlistId).collectAsState(initial = null)
            val loadedTracks = tracks.orEmpty()
            val playlistTitle = playlist?.name ?: stringResource(R.string.local_playlist_title)
            LocalPlaylistObjectScreen(
                title = playlistTitle,
                tracks = loadedTracks,
                onBackClick = { navController.navigateUp() },
                onPlayClick = {
                    playTracks(
                        tracks = loadedTracks,
                        index = 0,
                        tracklist = LocalTracklist(id = playlistId, name = playlistTitle),
                    )
                },
                loadTrackCover = ::loadTrackCover,
                onTrackClick = { index, _ ->
                    playTracks(
                        tracks = loadedTracks,
                        index = index,
                        tracklist = LocalTracklist(id = playlistId, name = playlistTitle),
                    )
                },
                isLoading = tracks == null ||
                    (tracks.isNullOrEmpty() && isSynchronizing),
                modifier = modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .background(LocalLibraryBackground),
            )
        }
        else -> {
            val tracks by repository.songs.collectAsState(initial = null)
            val loadedTracks = tracks.orEmpty()
            LocalLibraryScreen(
                title = allTracksTitle,
                playlists = null,
                tracks = loadedTracks,
                onPlaylistClick = {},
                loadTrackCover = ::loadTrackCover,
                onTrackClick = { index, _ ->
                    playTracks(
                        tracks = loadedTracks,
                        index = index,
                        tracklist = LocalTracklist(id = "local:all", name = allTracksTitle),
                    )
                },
                isLoading = tracks == null ||
                    (tracks.isNullOrEmpty() && isSynchronizing),
                modifier = modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .background(LocalLibraryBackground),
            )
        }
    }
}

private val LocalLibraryBackground = Color(0xFF101116)
private const val TRACK_COVER_SIZE_PX = 180
private const val TAG = "LocalLibraryRoute"
