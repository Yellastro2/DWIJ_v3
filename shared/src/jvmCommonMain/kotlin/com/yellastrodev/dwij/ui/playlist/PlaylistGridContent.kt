package com.yellastrodev.dwij.ui.playlist

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.yellastrodev.dwij.HomeMusicSource
import com.yellastrodev.dwij.resources.Res
import com.yellastrodev.dwij.resources.common_ok
import com.yellastrodev.dwij.resources.ic_player_play_v2
import com.yellastrodev.dwij.resources.ic_playlist_create
import com.yellastrodev.dwij.resources.ic_playlist_liked
import com.yellastrodev.dwij.resources.list_loading_placeholder
import com.yellastrodev.dwij.resources.playlists_cancel
import com.yellastrodev.dwij.resources.playlists_create_failed
import com.yellastrodev.dwij.resources.playlists_delete_message
import com.yellastrodev.dwij.resources.playlists_delete_title
import com.yellastrodev.dwij.resources.playlists_local_add_unavailable
import com.yellastrodev.dwij.resources.playlists_refresh_failed
import com.yellastrodev.dwij.resources.playlists_remove_track_confirm
import com.yellastrodev.dwij.resources.playlists_remove_track_message
import com.yellastrodev.dwij.resources.playlists_remove_track_title
import com.yellastrodev.dwij.resources.playlists_track_add_failed
import com.yellastrodev.dwij.resources.playlists_track_load_failed
import com.yellastrodev.dwij.resources.playlists_track_remove_failed
import com.yellastrodev.dwij.ui.MusicSourceSelector
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.ui.tooling.preview.Preview

/**
 * Полностью shared presentation-слой сетки плейлистов.
 *
 * Не зависит от Android R, Context или android.R.
 */
@Composable
fun PlaylistGridContent(
    state: PlaylistGridRouteState,
    actions: PlaylistGridRouteActions,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState =
        remember { SnackbarHostState() }

    val snackbarMessage =
        state.message
            ?.message
            ?.let { message ->
                stringResource(
                    message.stringResource(),
                )
            }

    LaunchedEffect(
        state.message?.id,
        snackbarMessage,
    ) {
        if (snackbarMessage != null) {
            snackbarHostState.showSnackbar(
                snackbarMessage,
            )
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        PlaylistGridScreen(
            title = state.title,
            items = state.items,
            selectedSource = state.selectedSource,
            onSourceSelected =
                actions.onSourceSelected,
            onBackClick =
                actions.onBackClick,
            onItemClick =
                actions.onItemClick,
            onItemLongClick =
                actions.onItemLongClick,
            emptyMessage =
                state.emptyMessage,
            loadingMessage = stringResource(
                Res.string.list_loading_placeholder,
            ),
            sourceSelector = {
                    selectedSource,
                    onSourceSelected,
                ->

                MusicSourceSelector(
                    selectedSource =
                        selectedSource,
                    onSourceSelected =
                        onSourceSelected,
                    modifier =
                        Modifier.fillMaxWidth(),
                )
            },
            itemContent = {
                    item,
                    coverState,
                    onClick,
                    onLongClick,
                    itemModifier,
                ->

                PlaylistGridScreenItemContent(
                    item = item,
                    coverState = coverState,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    modifier = itemModifier,
                )
            },
            loadCover =
                actions.loadCover,
            showSourceSelector =
                state.showSourceSelector,
            modifier = Modifier.fillMaxSize(),
            isLoading =
                state.isLoading,
            isRefreshing =
                state.isRefreshing,
            onRefresh =
                actions.onRefresh,
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
        )
    }

    when (val dialog = state.dialog) {
        is PlaylistGridDialogState.Create -> {
            CreatePlaylistDialog(
                source = dialog.source,
                isCreating =
                    dialog.isCreating,
                onDismiss =
                    actions.onDialogDismiss,
                onCreate =
                    actions.onCreatePlaylist,
            )
        }

        is PlaylistGridDialogState.RemoveTrack -> {
            AlertDialog(
                onDismissRequest =
                    actions.onDialogDismiss,
                title = {
                    Text(
                        stringResource(
                            Res.string
                                .playlists_remove_track_title,
                        ),
                    )
                },
                text = {
                    Text(
                        stringResource(
                            Res.string
                                .playlists_remove_track_message,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick =
                            actions.onRemoveTrackConfirm,
                    ) {
                        Text(
                            stringResource(
                                Res.string
                                    .playlists_remove_track_confirm,
                            ),
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick =
                            actions.onDialogDismiss,
                    ) {
                        Text(
                            stringResource(
                                Res.string
                                    .playlists_cancel,
                            ),
                        )
                    }
                },
            )
        }

        is PlaylistGridDialogState.DeleteInfo -> {
            AlertDialog(
                onDismissRequest =
                    actions.onDialogDismiss,
                title = {
                    Text(
                        stringResource(
                            Res.string
                                .playlists_delete_title,
                        ),
                    )
                },
                text = {
                    Text(
                        dialog.playlistTitle +
                                "\n\n" +
                                stringResource(
                                    Res.string
                                        .playlists_delete_message,
                                ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick =
                            actions.onDeleteInfoConfirm,
                    ) {
                        Text(
                            stringResource(
                                Res.string.common_ok,
                            ),
                        )
                    }
                },
            )
        }

        null -> Unit
    }
}

@Composable
private fun PlaylistGridScreenItemContent(
    item: PlaylistGridScreenItem,
    coverState: PlaylistCoverState,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val fallbackContent:
            (@Composable () -> Unit)? =
        item.fallbackArtwork
            ?.let { artwork ->
                {
                    Image(
                        painter = painterResource(
                            artwork.drawableResource(),
                        ),
                        contentDescription = null,
                        contentScale =
                            ContentScale.Crop,
                        modifier =
                            Modifier.fillMaxSize(),
                    )
                }
            }

    val artworkContent:
            (@Composable () -> Unit)? =
        item.artwork
            ?.let { artwork ->
                {
                    Image(
                        painter = painterResource(
                            artwork.drawableResource(),
                        ),
                        contentDescription = null,
                        contentScale =
                            ContentScale.Fit,
                        modifier =
                            Modifier.fillMaxSize(),
                    )
                }
            }

    PlaylistGridItem(
        item = PlaylistGridItemUiModel(
            title = item.title,
            details = item.details,
            highlighted =
                item.highlighted,
        ),
        coverState = coverState,
        onClick = onClick,
        onLongClick = onLongClick,
        fallbackContent =
            fallbackContent,
        artworkContent =
            artworkContent,
        modifier = modifier,
    )
}

private fun PlaylistGridArtwork.drawableResource():
        DrawableResource =
    when (this) {
        PlaylistGridArtwork.Create ->
            Res.drawable.ic_playlist_create

        PlaylistGridArtwork.Liked ->
            Res.drawable.ic_playlist_liked

        PlaylistGridArtwork.PlayerFallback ->
            Res.drawable.ic_player_play_v2
    }

private fun PlaylistGridMessage.stringResource():
        StringResource =
    when (this) {
        PlaylistGridMessage.TrackLoadFailed ->
            Res.string.playlists_track_load_failed

        PlaylistGridMessage.CreateFailed ->
            Res.string.playlists_create_failed

        PlaylistGridMessage.LocalAddUnavailable ->
            Res.string.playlists_local_add_unavailable

        PlaylistGridMessage.TrackAddFailed ->
            Res.string.playlists_track_add_failed

        PlaylistGridMessage.TrackRemoveFailed ->
            Res.string.playlists_track_remove_failed

        PlaylistGridMessage.RefreshFailed ->
            Res.string.playlists_refresh_failed
    }

@Preview(
    name = "Playlist grid content",
    widthDp = 360,
    heightDp = 780,
)
@Composable
private fun PlaylistGridContentPreview() {
    PlaylistGridContent(
        state = PlaylistGridRouteState(
            title = "Плейлисты",
            items = listOf(
                PlaylistGridScreenItem(
                    id = "create",
                    title = "Создать плейлист",
                    artwork =
                        PlaylistGridArtwork.Create,
                    isCreateAction = true,
                ),
                PlaylistGridScreenItem(
                    id = "liked",
                    title = "Мне нравится",
                    details =
                        "148 треков\n9 ч 12 мин",
                    artwork =
                        PlaylistGridArtwork.Liked,
                ),
                PlaylistGridScreenItem(
                    id = "night",
                    title = "Ночной движ",
                    details =
                        "28 треков\n1 ч 42 мин",
                    fallbackArtwork =
                        PlaylistGridArtwork
                            .PlayerFallback,
                ),
            ),
            selectedSource =
                HomeMusicSource.Yandex,
            showSourceSelector = true,
            emptyMessage =
                "Плейлистов пока нет",
            isLoading = false,
            isRefreshing = false,
            dialog = null,
            message = null,
        ),
        actions = PlaylistGridRouteActions(
            onSourceSelected = {},
            onBackClick = {},
            onItemClick = {},
            onItemLongClick = {},
            loadCover = { null },
            onRefresh = {},
            onDialogDismiss = {},
            onCreatePlaylist = { _, _ -> },
            onRemoveTrackConfirm = {},
            onDeleteInfoConfirm = {},
        ),
        modifier = Modifier.fillMaxSize(),
    )
}
