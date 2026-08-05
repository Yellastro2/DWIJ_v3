package com.yellastrodev.dwij.ui.playlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Платформонезависимые данные плитки плейлиста.
 *
 * Конкретные Android/desktop-ресурсы сюда не передаются.
 */
@Immutable
data class PlaylistGridItemUiModel(
    val title: String,
    val details: String = "",
    val highlighted: Boolean = false,
)

/**
 * Хранит загруженную обложку отдельно от остальных данных плитки.
 */
@Stable
class PlaylistCoverState(
    initialBitmap: ImageBitmap? = null,
) {
    var bitmap by mutableStateOf(initialBitmap)
        private set

    private var isLoading = false

    /**
     * Разрешает начать загрузку, если обложки ещё нет
     * и другой запрос не выполняется.
     */
    fun tryStartLoading(): Boolean {
        if (bitmap != null || isLoading) {
            return false
        }

        isLoading = true

        return true
    }

    /**
     * Завершает загрузку и публикует новую обложку.
     */
    fun finishLoading(
        loadedBitmap: ImageBitmap?,
    ) {
        bitmap = loadedBitmap
        isLoading = false
    }

    /**
     * Снимает флаг после отменённой или неудачной загрузки.
     */
    fun cancelLoading() {
        isLoading = false
    }
}

/**
 * Общая Compose-плитка плейлиста.
 *
 * [fallbackContent] и [artworkContent] передаются платформенным слоем,
 * поскольку Android и desktop используют разные системы ресурсов.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistGridItem(
    item: PlaylistGridItemUiModel,
    coverState: PlaylistCoverState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    fallbackContent: (@Composable () -> Unit)? = null,
    artworkContent: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        PlaylistCover(
            coverState = coverState,
            fallbackContent = fallbackContent,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(
                    start = 6.dp,
                    top = 6.dp,
                    end = 10.dp,
                ),
        ) {
            PlaylistGridTextPlate(
                backgroundColor = if (item.highlighted) {
                    PlaylistHighlightedTitleBackground
                } else {
                    PlaylistTitleBackground
                },
            ) {
                Text(
                    text = item.title,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 20.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        horizontal = 9.dp,
                        vertical = 4.dp,
                    ),
                )
            }

            if (item.details.isNotBlank()) {
                PlaylistGridTextPlate(
                    backgroundColor = PlaylistDetailsBackground,
                ) {
                    Text(
                        text = item.details,
                        color = Color(0xFFE9F8FF),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(
                            horizontal = 8.dp,
                            vertical = 4.dp,
                        ),
                    )
                }
            }
        }

        if (artworkContent != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(7.dp)
                    .size(54.dp)
                    .alpha(0.92f),
            ) {
                artworkContent()
            }
        }
    }
}

/**
 * Показывает загруженную обложку либо платформенную заглушку.
 */
@Composable
private fun PlaylistCover(
    coverState: PlaylistCoverState,
    fallbackContent: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val cover = coverState.bitmap

    when {
        cover != null -> {
            Image(
                bitmap = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier,
            )
        }

        fallbackContent != null -> {
            Box(
                modifier = modifier,
            ) {
                fallbackContent()
            }
        }

        else -> {
            Box(
                modifier = modifier.background(
                    PlaylistBackground,
                ),
            )
        }
    }
}

/**
 * Добавляет полупрозрачную подложку под текст.
 */
@Composable
private fun PlaylistGridTextPlate(
    backgroundColor: Color,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.background(
            color = backgroundColor,
            shape = RoundedCornerShape(4.dp),
        ),
    ) {
        content()
    }
}

private val PlaylistBackground =
    Color(0xFF03040F)

private val PlaylistTitleBackground =
    Color(0xB30A0714)

private val PlaylistHighlightedTitleBackground =
    Color(0xB31A0B22)

private val PlaylistDetailsBackground =
    Color(0x99001622)