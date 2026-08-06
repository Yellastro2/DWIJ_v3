package com.yellastrodev.dwij

import com.yellastrodev.dwij.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource as sharedPainterResource
import androidx.annotation.StringRes
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yellastrodev.dwij.ui.playlist.PlaylistCoverState
import com.yellastrodev.dwij.ui.playlist.PlaylistGridArtwork
import com.yellastrodev.dwij.ui.playlist.PlaylistGridDialogState
import com.yellastrodev.dwij.ui.playlist.PlaylistGridItem as SharedPlaylistGridItem
import com.yellastrodev.dwij.ui.playlist.PlaylistGridItemUiModel
import com.yellastrodev.dwij.ui.playlist.PlaylistGridMessage
import com.yellastrodev.dwij.ui.playlist.PlaylistGridRouteActions
import com.yellastrodev.dwij.ui.playlist.PlaylistGridRouteState
import com.yellastrodev.dwij.ui.playlist.PlaylistGridScreen as SharedPlaylistGridScreen
import com.yellastrodev.dwij.ui.playlist.PlaylistGridScreenItem

/**
 * Временный Android presentation-мост.
 *
 * Вся экранная логика уже живёт в shared PlaylistGridRoute. Здесь остаются только
 * Android string resources и подключение существующих composable-компонентов.
 */
@Composable
fun AndroidPlaylistGridContent(
    state: PlaylistGridRouteState,
    actions: PlaylistGridRouteActions,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarMessage = state.message?.message?.let { message ->
        stringResource(message.stringResId())
    }

    LaunchedEffect(state.message?.id, snackbarMessage) {
        if (snackbarMessage != null) {
            snackbarHostState.showSnackbar(snackbarMessage)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        PlaylistGridScreen(
            title = state.title,
            items = state.items,
            selectedSource = state.selectedSource,
            onSourceSelected = actions.onSourceSelected,
            showSourceSelector = state.showSourceSelector,
            onBackClick = actions.onBackClick,
            onItemClick = actions.onItemClick,
            onItemLongClick = actions.onItemLongClick,
            loadCover = actions.loadCover,
            emptyMessage = state.emptyMessage,
            isLoading = state.isLoading,
            isRefreshing = state.isRefreshing,
            onRefresh = actions.onRefresh,
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
                isCreating = dialog.isCreating,
                onDismiss = actions.onDialogDismiss,
                onCreate = actions.onCreatePlaylist,
            )
        }

        is PlaylistGridDialogState.RemoveTrack -> {
            AlertDialog(
                onDismissRequest = actions.onDialogDismiss,
                title = {
                    Text(stringResource(R.string.playlists_remove_track_title))
                },
                text = {
                    Text(stringResource(R.string.playlists_remove_track_message))
                },
                confirmButton = {
                    TextButton(onClick = actions.onRemoveTrackConfirm) {
                        Text(stringResource(R.string.playlists_remove_track_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = actions.onDialogDismiss) {
                        Text(stringResource(R.string.playlists_cancel))
                    }
                },
            )
        }

        is PlaylistGridDialogState.DeleteInfo -> {
            AlertDialog(
                onDismissRequest = actions.onDialogDismiss,
                title = {
                    Text(stringResource(R.string.playlists_delete_title))
                },
                text = {
                    Text(
                        "${dialog.playlistTitle}\n\n" +
                                stringResource(R.string.playlists_delete_message),
                    )
                },
                confirmButton = {
                    TextButton(onClick = actions.onDeleteInfoConfirm) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            )
        }

        null -> Unit
    }
}

/**
 * Тонкий Android-адаптер общей разметки с shared-графикой и текущим MusicSourceSelector.
 */
@Composable
fun PlaylistGridScreen(
    title: String,
    items: List<PlaylistGridScreenItem>,
    selectedSource: HomeMusicSource,
    onSourceSelected: (HomeMusicSource) -> Unit,
    showSourceSelector: Boolean = true,
    onBackClick: () -> Unit,
    onItemClick: (PlaylistGridScreenItem) -> Unit,
    onItemLongClick: (PlaylistGridScreenItem) -> Unit,
    loadCover: suspend (String) -> ImageBitmap? = { null },
    emptyMessage: String,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    val loadingMessage = stringResource(R.string.list_loading_placeholder)

    SharedPlaylistGridScreen(
        title = title,
        items = items,
        selectedSource = selectedSource,
        onSourceSelected = onSourceSelected,
        onBackClick = onBackClick,
        onItemClick = onItemClick,
        onItemLongClick = onItemLongClick,
        emptyMessage = emptyMessage,
        loadingMessage = loadingMessage,
        sourceSelector = { source, onSelected ->
            MusicSourceSelector(
                selectedSource = source,
                onSourceSelected = onSelected,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        itemContent = {
                item,
                coverState,
                itemOnClick,
                itemOnLongClick,
                itemModifier,
            ->
            AndroidPlaylistGridItem(
                item = item,
                coverState = coverState,
                onClick = itemOnClick,
                onLongClick = itemOnLongClick,
                modifier = itemModifier,
            )
        },
        loadCover = loadCover,
        showSourceSelector = showSourceSelector,
        modifier = modifier,
        isLoading = isLoading,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
    )
}

@Composable
private fun AndroidPlaylistGridItem(
    item: PlaylistGridScreenItem,
    coverState: PlaylistCoverState,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val fallbackContent: (@Composable () -> Unit)? =
        item.fallbackArtwork?.let { artwork ->
            {
                Image(
                    painter = sharedPainterResource(artwork.drawableResource()),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

    val artworkContent: (@Composable () -> Unit)? =
        item.artwork?.let { artwork ->
            {
                Image(
                    painter = sharedPainterResource(artwork.drawableResource()),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

    SharedPlaylistGridItem(
        item = PlaylistGridItemUiModel(
            title = item.title,
            details = item.details,
            highlighted = item.highlighted,
        ),
        coverState = coverState,
        onClick = onClick,
        onLongClick = onLongClick,
        fallbackContent = fallbackContent,
        artworkContent = artworkContent,
        modifier = modifier,
    )
}

private fun PlaylistGridArtwork.drawableResource(): DrawableResource = when (this) {
    PlaylistGridArtwork.Create -> Res.drawable.ic_playlist_create
    PlaylistGridArtwork.Liked -> Res.drawable.ic_playlist_liked
    PlaylistGridArtwork.PlayerFallback -> Res.drawable.ic_player_play_v2
}

@StringRes
private fun PlaylistGridMessage.stringResId(): Int = when (this) {
    PlaylistGridMessage.TrackLoadFailed -> R.string.playlists_track_load_failed
    PlaylistGridMessage.CreateFailed -> R.string.playlists_create_failed
    PlaylistGridMessage.LocalAddUnavailable -> R.string.playlists_local_add_unavailable
    PlaylistGridMessage.TrackAddFailed -> R.string.playlists_track_add_failed
    PlaylistGridMessage.TrackRemoveFailed -> R.string.playlists_track_remove_failed
    PlaylistGridMessage.RefreshFailed -> R.string.playlists_refresh_failed
}

@Preview(
    name = "Playlist grid screen",
    widthDp = 360,
    heightDp = 780,
    showBackground = true,
    backgroundColor = 0xFF03040F,
)
@Composable
private fun PlaylistGridScreenPreview() {
    PlaylistGridScreen(
        title = "Плейлисты",
        items = listOf(
            PlaylistGridScreenItem(
                id = "create",
                title = "Создать плейлист",
                artwork = PlaylistGridArtwork.Create,
                isCreateAction = true,
            ),
            PlaylistGridScreenItem(
                id = "liked",
                title = "Мне нравится",
                details = "148 треков\n9 ч 12 мин",
                artwork = PlaylistGridArtwork.Liked,
            ),
            PlaylistGridScreenItem(
                id = "night",
                title = "Ночной движ",
                details = "28 треков\n1 ч 42 мин",
            ),
            PlaylistGridScreenItem(
                id = "road",
                title = "В дорогу",
                details = "41 трек\n2 ч 36 мин",
            ),
            PlaylistGridScreenItem(
                id = "focus",
                title = "Фокус",
                details = "19 треков\n58 мин",
            ),
        ),
        selectedSource = HomeMusicSource.Yandex,
        onSourceSelected = {},
        onBackClick = {},
        onItemClick = {},
        onItemLongClick = {},
        emptyMessage = "Плейлистов пока нет",
        modifier = Modifier.fillMaxSize(),
    )
}
