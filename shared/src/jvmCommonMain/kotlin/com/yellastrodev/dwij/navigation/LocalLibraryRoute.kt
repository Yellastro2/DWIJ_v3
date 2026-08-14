package com.yellastrodev.dwij.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntity
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.models.LocalLibraryContent
import com.yellastrodev.dwij.models.LocalLibraryEvent
import com.yellastrodev.dwij.models.LocalLibraryModel
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.resources.Res
import com.yellastrodev.dwij.resources.local_all_tracks_title
import com.yellastrodev.dwij.resources.local_playlist_title
import com.yellastrodev.dwij.resources.local_playlists_title
import com.yellastrodev.dwij.resources.local_track_hide_action
import com.yellastrodev.dwij.resources.local_track_hide_confirm_message
import com.yellastrodev.dwij.resources.local_track_hide_confirm_title
import com.yellastrodev.dwij.resources.playlists_cancel
import com.yellastrodev.dwij.ui.LocalLibraryScreen
import com.yellastrodev.dwij.ui.LocalTrackCollectionObjectScreen
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.compose.resources.stringResource

/** Shared-route локальных списков, всех треков и локального плейлиста. */
@Composable
fun LocalLibraryRoute(
    component: DwijComponent,
    playerModel: PlayerModel,
    platform: LocalLibraryPlatform,
    mode: String,
    playlistId: String?,
    onOpenPlayer: () -> Unit,
    onOpenPlaylist: (playlistId: String) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = remember(mode, playlistId) {
        when {
            mode == DwijDestination.LOCAL_MODE_PLAYLISTS ->
                LocalLibraryContent.PLAYLISTS

            mode == DwijDestination.LOCAL_MODE_PLAYLIST &&
                playlistId != null ->
                LocalLibraryContent.PLAYLIST

            else -> LocalLibraryContent.ALL_TRACKS
        }
    }

    val modelFactory = remember(
        component,
        content,
        playlistId,
    ) {
        LocalLibraryModel.Factory(
            repository = component.localMusicRepository,
            playerRepository = component.playerRepo,
            content = content,
            playlistId = playlistId,
        )
    }

    val localLibraryModel = viewModel<LocalLibraryModel>(
        key = "local-library:$mode:${playlistId.orEmpty()}",
        factory = modelFactory,
    )

    val state by localLibraryModel.state.collectAsState()
    val allTracksTitle = stringResource(Res.string.local_all_tracks_title)
    val currentOnOpenPlayer by rememberUpdatedState(onOpenPlayer)

    LaunchedEffect(localLibraryModel) {
        localLibraryModel.events.collect { event ->
            when (event) {
                LocalLibraryEvent.OpenPlayer ->
                    currentOnOpenPlayer()
            }
        }
    }

    LaunchedEffect(state.hideError, platform) {
        if (state.hideError != null) {
            platform.showTrackHideFailed()
            localLibraryModel.consumeHideError()
        }
    }

    suspend fun loadTrackCover(
        song: Song,
    ): ImageBitmap? {
        return playerModel
            .cover(
                song = song,
                maxEdgePx = TRACK_COVER_SIZE_PX,
            )
            .firstOrNull()
    }

    fun openPlaylist(
        playlist: LocalPlaylistEntity,
    ) {
        onOpenPlaylist(playlist.playlistId)
    }

    when (content) {
        LocalLibraryContent.PLAYLISTS -> {
            val playlists = state.playlists

            LocalLibraryScreen(
                title = stringResource(Res.string.local_playlists_title),
                playlists = playlists.orEmpty(),
                tracks = null,
                onBackClick = onBackClick,
                onPlaylistClick = ::openPlaylist,
                onTrackClick = { _, _ -> },
                onTrackHideRequest = {},
                loadTrackCover = ::loadTrackCover,
                isLoading =
                    playlists == null ||
                        (
                            playlists.isEmpty() &&
                                state.isSynchronizing
                        ),
                modifier = modifier.localLibraryBackground(),
            )
        }

        LocalLibraryContent.PLAYLIST -> {
            val tracks = state.tracks
            val loadedTracks = tracks.orEmpty()
            val playlistTitle = state.playlist?.name
                ?: stringResource(Res.string.local_playlist_title)

            LocalTrackCollectionObjectScreen(
                title = playlistTitle,
                tracks = loadedTracks,
                onBackClick = onBackClick,
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
                onTrackHideRequest =
                    localLibraryModel::requestTrackHide,
                isLoading =
                    tracks == null ||
                        (
                            tracks.isEmpty() &&
                                state.isSynchronizing
                        ),
                modifier = modifier.localLibraryBackground(),
            )
        }

        LocalLibraryContent.ALL_TRACKS -> {
            val tracks = state.tracks
            val loadedTracks = tracks.orEmpty()

            LocalTrackCollectionObjectScreen(
                title = allTracksTitle,
                tracks = loadedTracks,
                onBackClick = onBackClick,
                onPlayClick = {
                    localLibraryModel.play(
                        index = 0,
                        tracklistName = allTracksTitle,
                    )
                },
                loadTrackCover = ::loadTrackCover,
                onTrackClick = { index, _ ->
                    localLibraryModel.play(
                        index = index,
                        tracklistName = allTracksTitle,
                    )
                },
                onTrackHideRequest =
                    localLibraryModel::requestTrackHide,
                isLoading =
                    tracks == null ||
                        (
                            tracks.isEmpty() &&
                                state.isSynchronizing
                        ),
                modifier = modifier.localLibraryBackground(),
            )
        }
    }

    state.pendingHideSong?.let { track ->
        AlertDialog(
            onDismissRequest =
                localLibraryModel::dismissTrackHide,
            title = {
                Text(
                    stringResource(
                        Res.string.local_track_hide_confirm_title,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        Res.string.local_track_hide_confirm_message,
                        track.title,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick =
                        localLibraryModel::confirmTrackHide,
                ) {
                    Text(
                        stringResource(
                            Res.string.local_track_hide_action,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick =
                        localLibraryModel::dismissTrackHide,
                ) {
                    Text(
                        stringResource(
                            Res.string.playlists_cancel,
                        ),
                    )
                }
            },
        )
    }
}

@Composable
private fun Modifier.localLibraryBackground(): Modifier =
    fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .background(LocalLibraryBackground)

private val LocalLibraryBackground = Color(0xFF101116)
private const val TRACK_COVER_SIZE_PX = 180
