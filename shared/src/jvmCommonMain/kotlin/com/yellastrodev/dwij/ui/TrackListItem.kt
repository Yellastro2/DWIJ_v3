package com.yellastrodev.dwij.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yellastrodev.dwij.TrackListItemUiModel
import com.yellastrodev.dwij.resources.*
import com.yellastrodev.dwij.ui.theme.DwijColors
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.painterResource

/** Источник, который нужно обозначить справа в строке выбора трека. */
enum class TrackSourceIndicator {
    YANDEX,
    LOCAL,
}

/**
 * Универсальная строка трека. В обычном списке показывает доступность и дубли,
 * а в диалоге источников — тип источника и галочку локального выбора. [onLongClick]
 * используется только родительским списком для открытия контекстного меню.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun TrackListItem(
    item: TrackListItemUiModel,
    coverState: TrackCoverState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    sourceIndicator: TrackSourceIndicator? = null,
    isSelected: Boolean = false,
) {
    val showMultiplicityIndicator = sourceIndicator == null &&
        (item.hasMultipleSources || item.hasUnresolvedMatchCandidate)
    val showYandexIndicator = sourceIndicator == TrackSourceIndicator.YANDEX ||
        (sourceIndicator == null && item.isYandexUnavailable)
    val topIndicatorCount = listOf(
        showMultiplicityIndicator,
        showYandexIndicator,
        sourceIndicator == TrackSourceIndicator.LOCAL,
    ).count { it }
    val hasRightDecoration = topIndicatorCount > 0 || isSelected
    val titleColor = if (item.isPlaybackBlocked) {
        DwijColors.White.copy(alpha = 0.34f)
    } else {
        DwijColors.White
    }
    val artistColor = if (item.isPlaybackBlocked) {
        DwijColors.SecondaryText.copy(alpha = 0.38f)
    } else {
        DwijColors.SecondaryText
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            TrackCover(
                coverState = coverState,
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(5.dp)),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start = 12.dp,
                        end = when {
                            topIndicatorCount >= 2 -> 54.dp
                            hasRightDecoration -> 30.dp
                            else -> 8.dp
                        },
                    ),
            ) {
                Text(
                    text = item.title,
                    color = titleColor,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.artist,
                    color = artistColor,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }

        if (topIndicatorCount > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 5.dp, end = 8.dp),
            ) {
                if (showMultiplicityIndicator) {
                    MultipleSourcesIndicator(modifier = Modifier.size(22.dp))
                }
                when {
                    showYandexIndicator -> YandexSourceIndicator(
                        isUnavailable = item.isYandexUnavailable,
                    )
                    sourceIndicator == TrackSourceIndicator.LOCAL -> LocalSourceIndicator()
                }
            }
        }
        if (isSelected) {
            TrackSelectionIndicator(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 9.dp, bottom = 7.dp),
            )
        }
    }
}

/** Рисует растеризованную SVG-букву «Я» и при необходимости перечёркивает её. */
@Composable
private fun YandexSourceIndicator(
    isUnavailable: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.size(22.dp)) {
        Image(
            painter = painterResource(Res.drawable.ic_source_yandex),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
        )
        if (isUnavailable) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val inset = 3.dp.toPx()
                drawLine(
                    color = DwijColors.TrackUnavailable,
                    start = Offset(inset, size.height - inset),
                    end = Offset(size.width - inset, inset),
                    strokeWidth = 1.7.dp.toPx(),
                    cap = StrokeCap.Square,
                )
            }
        }
    }
}

/** Показывает минимальный SVG-диск для локального MediaStore. */
@Composable
private fun LocalSourceIndicator(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(Res.drawable.ic_source_local_storage),
        contentDescription = null,
        modifier = modifier.size(22.dp),
    )
}

/** Рисует галочку выбранного варианта под его source-индикатором. */
@Composable
private fun TrackSelectionIndicator(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(18.dp)) {
        val stroke = 2.dp.toPx()
        drawLine(
            color = DwijColors.CyanBright,
            start = Offset(size.width * 0.16f, size.height * 0.52f),
            end = Offset(size.width * 0.42f, size.height * 0.78f),
            strokeWidth = stroke,
            cap = StrokeCap.Square,
        )
        drawLine(
            color = DwijColors.Pink,
            start = Offset(size.width * 0.42f, size.height * 0.78f),
            end = Offset(size.width * 0.86f, size.height * 0.2f),
            strokeWidth = stroke,
            cap = StrokeCap.Square,
        )
        drawLine(
            color = DwijColors.White,
            start = Offset(size.width * 0.18f, size.height * 0.48f),
            end = Offset(size.width * 0.43f, size.height * 0.71f),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Square,
        )
        drawLine(
            color = DwijColors.White,
            start = Offset(size.width * 0.43f, size.height * 0.71f),
            end = Offset(size.width * 0.82f, size.height * 0.22f),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Square,
        )
    }
}

/** Отдельное стабильное состояние обложки: его изменение перезапускает только [TrackCover]. */
@Stable
class TrackCoverState(initialBitmap: ImageBitmap? = null) {
    var bitmap by mutableStateOf(initialBitmap)
        private set

    private var isLoading = false

    internal fun tryStartLoading(): Boolean {
        if (bitmap != null || isLoading) return false
        isLoading = true
        return true
    }

    internal fun finishLoading(loadedBitmap: ImageBitmap?) {
        bitmap = loadedBitmap
        isLoading = false
    }

    internal fun cancelLoading() {
        isLoading = false
    }
}

/** Загружает обложку в жизненном цикле конкретной скомпонованной строки. */
@Composable
fun TrackCoverLoader(
    trackId: String,
    coverState: TrackCoverState,
    loadCover: suspend (trackId: String) -> ImageBitmap?,
) {
    val logger = LocalYamLogger.current
    val currentLoadCover by rememberUpdatedState(loadCover)
    LaunchedEffect(trackId, coverState) {
        if (!coverState.tryStartLoading()) return@LaunchedEffect
        var completed = false
        try {
            val loadedCover = try {
                currentLoadCover(trackId)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.error(
                    TRACK_LIST_ITEM_TAG,
                    "[TrackCoverLoader] Не удалось загрузить обложку trackId=$trackId",
                    error,
                )
                null
            }
            coverState.finishLoading(loadedCover)
            completed = true
        } finally {
            if (!completed) coverState.cancelLoading()
        }
    }
}

/** Единственная часть строки, читающая изменяемое bitmap-состояние. */
@Composable
private fun TrackCover(
    coverState: TrackCoverState,
    modifier: Modifier = Modifier,
) {
    val cover = coverState.bitmap
    if (cover != null) {
        Image(
            bitmap = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        TrackCoverPlaceholder(modifier)
    }
}

/** Рисует лёгкую векторную заглушку вместо отдельного bitmap-ресурса. */
@Composable
private fun TrackCoverPlaceholder(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.background(DwijColors.TrackCoverBackground),
    ) {
        Canvas(modifier = Modifier.size(27.dp)) {
            val stroke = 2.2.dp.toPx()
            val cyanOffset = 1.4.dp.toPx()
            fun drawNote(color: Color, offsetX: Float) {
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.42f + offsetX, size.height * 0.24f),
                    end = Offset(size.width * 0.42f + offsetX, size.height * 0.72f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.42f + offsetX, size.height * 0.24f),
                    end = Offset(size.width * 0.76f + offsetX, size.height * 0.17f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
                drawCircle(
                    color = color,
                    radius = size.width * 0.13f,
                    center = Offset(size.width * 0.29f + offsetX, size.height * 0.76f),
                )
            }
            drawNote(DwijColors.TrackGlitchCyan, cyanOffset)
            drawNote(DwijColors.TrackGlitchPink, -cyanOffset)
            drawNote(DwijColors.White, 0f)
        }
    }
}

private const val TRACK_LIST_ITEM_TAG = "TrackListItem"
