package com.yellastrodev.dwij

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.yellastrodev.dwij.ui.playlist.PlaylistCoverState
import com.yellastrodev.dwij.ui.playlist.PlaylistGridEntry
import com.yellastrodev.dwij.ui.playlist.PlaylistGridItem as SharedPlaylistGridItem
import com.yellastrodev.dwij.ui.playlist.PlaylistGridItemUiModel
import com.yellastrodev.dwij.ui.playlist.PlaylistGridScreen as SharedPlaylistGridScreen

/**
 * Android-модель плитки.
 *
 * Общие признаки элемента реализуют PlaylistGridEntry.
 * Android resource ID остаются только в app.
 */
@Immutable
data class PlaylistGridScreenItem(
    override val id: String,
    val title: String,
    val details: String = "",
    override val shouldLoadCover: Boolean = false,
    @DrawableRes
    val fallbackCoverResId: Int? = null,
    @DrawableRes
    val artworkResId: Int? = null,
    val highlighted: Boolean = false,
    override val isCreateAction: Boolean = false,
) : PlaylistGridEntry

/**
 * Тонкий Android-адаптер общего экрана.
 *
 * Здесь остаются только:
 * - Android string resources;
 * - Android drawable resources;
 * - Android-реализация MusicSourceSelector.
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
    onItemLongClick:
        (PlaylistGridScreenItem) -> Unit,
    loadCover:
    suspend (String) -> ImageBitmap? = {
        null
    },
    emptyMessage: String,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    val loadingMessage = stringResource(
        R.string.list_loading_placeholder,
    )

    SharedPlaylistGridScreen(
        title = title,
        items = items,
        selectedSource = selectedSource,
        onSourceSelected =
            onSourceSelected,
        onBackClick = onBackClick,
        onItemClick = onItemClick,
        onItemLongClick =
            onItemLongClick,
        emptyMessage = emptyMessage,
        loadingMessage = loadingMessage,

        sourceSelector = {
                source,
                onSelected,
            ->

            MusicSourceSelector(
                selectedSource = source,
                onSourceSelected =
                    onSelected,
                modifier =
                    Modifier.fillMaxWidth(),
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
                onLongClick =
                    itemOnLongClick,
                modifier = itemModifier,
            )
        },

        loadCover = loadCover,
        showSourceSelector =
            showSourceSelector,
        modifier = modifier,
        isLoading = isLoading,
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
    )
}

/**
 * Единственный Android-мост для drawable resource ID.
 *
 * Отдельный app/PlaylistGridItem.kt больше не нужен:
 * этот адаптер используется только данным экраном.
 */
@Composable
private fun AndroidPlaylistGridItem(
    item: PlaylistGridScreenItem,
    coverState: PlaylistCoverState,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val fallbackContent:
            (@Composable () -> Unit)? =
        item.fallbackCoverResId?.let { resourceId ->
            {
                Image(
                    painter = painterResource(resourceId),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

    val artworkContent:
            (@Composable () -> Unit)? =
        item.artworkResId?.let { resourceId ->
            {
                Image(
                    painter = painterResource(resourceId),
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

/**
 * Android Studio Preview остаётся в app.
 */
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
                artworkResId =
                    R.drawable.ic_playlist_create,
                isCreateAction = true,
            ),
            PlaylistGridScreenItem(
                id = "liked",
                title = "Мне нравится",
                details =
                    "148 треков\n9 ч 12 мин",
                artworkResId =
                    R.drawable.ic_playlist_liked,
            ),
            PlaylistGridScreenItem(
                id = "night",
                title = "Ночной движ",
                details =
                    "28 треков\n1 ч 42 мин",
            ),
            PlaylistGridScreenItem(
                id = "road",
                title = "В дорогу",
                details =
                    "41 трек\n2 ч 36 мин",
            ),
            PlaylistGridScreenItem(
                id = "focus",
                title = "Фокус",
                details =
                    "19 треков\n58 мин",
            ),
        ),
        selectedSource =
            HomeMusicSource.Yandex,
        onSourceSelected = {},
        onBackClick = {},
        onItemClick = {},
        onItemLongClick = {},
        emptyMessage =
            "Плейлистов пока нет",
        modifier = Modifier.fillMaxSize(),
    )
}
