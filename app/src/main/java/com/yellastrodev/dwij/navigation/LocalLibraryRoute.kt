package com.yellastrodev.dwij.navigation

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yellastrodev.dwij.LocalLibraryScreen
import com.yellastrodev.dwij.LocalPlaylistObjectScreen
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntity
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.models.LocalLibraryContent
import com.yellastrodev.dwij.models.LocalLibraryEvent
import com.yellastrodev.dwij.models.LocalLibraryModel
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.work.LocalLibrarySyncWorker
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.flow.firstOrNull

/** Compose-route локальных списков, всех треков и объекта локального плейлиста. */
@Composable
fun LocalLibraryRoute(
    navController: NavHostController,
    playerModel: PlayerModel,
    mode: String,
    playlistId: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val application = context.applicationContext as yApplication

    val content = remember(mode, playlistId) {
        when {
            mode == DwijDestination.LOCAL_MODE_PLAYLISTS ->
                LocalLibraryContent.PLAYLISTS

            mode == DwijDestination.LOCAL_MODE_PLAYLIST && playlistId != null ->
                LocalLibraryContent.PLAYLIST

            else -> LocalLibraryContent.ALL_TRACKS
        }
    }

    val localLibraryModel = viewModel<LocalLibraryModel>(
        key = "local-library:$mode:${playlistId.orEmpty()}",
        factory = LocalLibraryModel.Factory(
            repository = application.localMusicRepository,
            playerRepository = application.playerRepo,
            content = content,
            playlistId = playlistId,
        ),
    )

    val state by localLibraryModel.state.collectAsState()
    val allTracksTitle = stringResource(R.string.local_all_tracks_title)

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                LocalLibrarySyncWorker.enqueueImmediate(
                    context.applicationContext,
                )
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(localLibraryModel, navController) {
        localLibraryModel.events.collect { event ->
            when (event) {
                LocalLibraryEvent.OpenPlayer ->
                    navController.navigate(DwijDestination.PLAYER)
            }
        }
    }

    LaunchedEffect(state.hideError) {
        if (state.hideError != null) {
            Toast.makeText(
                context,
                R.string.local_track_hide_failed,
                Toast.LENGTH_SHORT,
            ).show()

            localLibraryModel.consumeHideError()
        }
    }

    suspend fun loadTrackCover(song: Song): ImageBitmap? =
        playerModel
            .cover(
                song = song,
                maxEdgePx = TRACK_COVER_SIZE_PX,
            )
            .firstOrNull()

    fun openPlaylist(playlist: LocalPlaylistEntity) {
        navController.navigate(
            DwijDestination.localLibraryRoute(
                mode = DwijDestination.LOCAL_MODE_PLAYLIST,
                playlistId = playlist.playlistId,
            ),
        )
    }

    when (content) {
        LocalLibraryContent.PLAYLISTS -> {
            val playlists = state.playlists

            LocalLibraryScreen(
                title = stringResource(R.string.local_playlists_title),
                playlists = playlists.orEmpty(),
                tracks = null,
                onPlaylistClick = ::openPlaylist,
                onTrackClick = { _, _ -> },
                onTrackHideRequest = {},
                loadTrackCover = ::loadTrackCover,
                isLoading = playlists == null ||
                        (playlists.isEmpty() && state.isSynchronizing),
                modifier = modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .background(LocalLibraryBackground),
            )
        }

        LocalLibraryContent.PLAYLIST -> {
            val tracks = state.tracks
            val loadedTracks = tracks.orEmpty()
            val playlistTitle = state.playlist?.name
                ?: stringResource(R.string.local_playlist_title)

            LocalPlaylistObjectScreen(
                title = playlistTitle,
                tracks = loadedTracks,
                onBackClick = { navController.navigateUp() },
                onPlayClick = {
                    localLibraryModel.play(
                        index = 0,
                        tracklistName = playlistTitle,
                    )
                },
                loadTrackCover = ::loadTrackCover,
                onTrackClick = { index, _ ->
                    localLibraryModel.play(
                        index = index,
                        tracklistName = playlistTitle,
                    )
                },
                onTrackHideRequest = localLibraryModel::requestTrackHide,
                isLoading = tracks == null ||
                        (tracks.isEmpty() && state.isSynchronizing),
                modifier = modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .background(LocalLibraryBackground),
            )
        }

        LocalLibraryContent.ALL_TRACKS -> {
            val tracks = state.tracks
            val loadedTracks = tracks.orEmpty()

            LocalLibraryScreen(
                title = allTracksTitle,
                playlists = null,
                tracks = loadedTracks,
                onPlaylistClick = {},
                loadTrackCover = ::loadTrackCover,
                onTrackClick = { index, _ ->
                    localLibraryModel.play(
                        index = index,
                        tracklistName = allTracksTitle,
                    )
                },
                onTrackHideRequest = localLibraryModel::requestTrackHide,
                isLoading = tracks == null ||
                        (tracks.isEmpty() && state.isSynchronizing),
                modifier = modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .background(LocalLibraryBackground),
            )
        }
    }

    state.pendingHideSong?.let { track ->
        AlertDialog(
            onDismissRequest = localLibraryModel::dismissTrackHide,
            title = {
                Text(
                    stringResource(R.string.local_track_hide_confirm_title),
                )
            },
            text = {
                Text(
                    stringResource(
                        R.string.local_track_hide_confirm_message,
                        track.title,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = localLibraryModel::confirmTrackHide,
                ) {
                    Text(stringResource(R.string.local_track_hide_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = localLibraryModel::dismissTrackHide,
                ) {
                    Text(stringResource(R.string.playlists_cancel))
                }
            },
        )
    }
}

private val LocalLibraryBackground = Color(0xFF101116)
private const val TRACK_COVER_SIZE_PX = 180
