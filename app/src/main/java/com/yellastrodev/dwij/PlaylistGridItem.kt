package com.yellastrodev.dwij

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.yellastrodev.dwij.ui.playlist.PlaylistCoverState as SharedPlaylistCoverState
import com.yellastrodev.dwij.ui.playlist.PlaylistGridItem as SharedPlaylistGridItem
import com.yellastrodev.dwij.ui.playlist.PlaylistGridItemUiModel as SharedPlaylistGridItemUiModel

/**
 * Android-модель плитки.
 *
 * Drawable resource ID остаются в платформенном слое
 * и не попадают в shared.
 */
@Immutable
data class PlaylistGridItemUiModel(
    val title: String,
    val details: String = "",
    @DrawableRes
    val fallbackCoverResId: Int? = null,
    @DrawableRes
    val artworkResId: Int? = null,
    val highlighted: Boolean = false,
)

/**
 * Состояние обложки физически находится в shared.
 *
 * Typealias сохраняет прежнее имя, поэтому
 * PlaylistGridScreen менять пока не требуется.
 */
typealias PlaylistCoverState =
        SharedPlaylistCoverState

/**
 * Тонкий Android-адаптер ресурсов.
 *
 * Вся геометрия, тексты, клики и работа с обложкой
 * выполняются общим PlaylistGridItem из shared.
 */
@Composable
fun PlaylistGridItem(
    item: PlaylistGridItemUiModel,
    coverState: PlaylistCoverState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
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
        item = SharedPlaylistGridItemUiModel(
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