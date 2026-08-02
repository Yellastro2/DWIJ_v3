package com.yellastrodev.dwij

import android.util.Log
import androidx.annotation.DrawableRes
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Данные одной квадратной плитки плейлиста, не зависящие от экрана и его стейтов. */
@Immutable
data class PlaylistGridItemUiModel(
    val title: String,
    val details: String = "",
    @DrawableRes val fallbackCoverResId: Int? = null,
    @DrawableRes val artworkResId: Int? = null,
    val highlighted: Boolean = false,
)

/**
 * Хранит изменяемую обложку отдельно от неизменяемых данных плитки.
 *
 * [bitmap] читает только composable изображения, поэтому её обновление не инвалидирует
 * заголовок, подпись и кликабельный контейнер плитки. Флаг загрузки не является Compose-state:
 * он лишь не даёт запустить две coroutine для одного playlist id одновременно.
 */
@Stable
class PlaylistCoverState(initialBitmap: ImageBitmap? = null) {
    var bitmap by mutableStateOf(initialBitmap)
        private set

    private var isLoading = false

    /** Разрешает начать загрузку, только если обложки ещё нет и запрос не выполняется. */
    internal fun tryStartLoading(): Boolean {
        if (bitmap != null || isLoading) return false
        isLoading = true
        return true
    }

    /** Завершает запрос и публикует результат только для composable изображения. */
    internal fun finishLoading(loadedBitmap: ImageBitmap?) {
        bitmap = loadedBitmap
        isLoading = false
    }

    /** Снимает внутренний флаг после отмены или ошибки, чтобы позднее запрос можно было повторить. */
    internal fun cancelLoading() {
        isLoading = false
    }
}

/**
 * Показывает обложку плейлиста с компактными глич-плашками текста.
 *
 * Размер задаёт родитель: компонент лишь сохраняет квадратные пропорции, поэтому позже он
 * одинаково подходит и фиксированной плитке 150 dp, и адаптивной Compose-сетке.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistGridItem(
    item: PlaylistGridItemUiModel,
    coverState: PlaylistCoverState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val compositionStartedNanos = System.nanoTime()
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
            fallbackCoverResId = item.fallbackCoverResId,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 6.dp, top = 6.dp, end = 10.dp),
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
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
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
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        item.artworkResId?.let { artworkResId ->
            Image(
                painter = painterResource(artworkResId),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(7.dp)
                    .size(54.dp)
                    .alpha(0.92f),
            )
        }
    }
    PlaylistTileCompositionProfiler.record(System.nanoTime() - compositionStartedNanos)
}

/**
 * Единственная restart-группа плитки, которая читает bitmap-state и перезапускается при его смене.
 */
@Composable
private fun PlaylistCover(
    coverState: PlaylistCoverState,
    @DrawableRes fallbackCoverResId: Int?,
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
    } else if (fallbackCoverResId != null) {
        Image(
            painter = painterResource(fallbackCoverResId),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(
            modifier = modifier.background(PlaylistBackground),
        )
    }
}

/** Добавляет под текст простую полупрозрачную цветную подложку. */
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

/** Показывает плитку в её исходном размере 150 dp на фоне главного экрана. */
@Preview(
    name = "Playlist grid item",
    widthDp = 180,
    heightDp = 180,
    showBackground = true,
    backgroundColor = 0xFF03040F,
)
@Composable
private fun PlaylistGridItemPreview() {
    val coverState = remember { PlaylistCoverState() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(PlaylistBackground),
    ) {
        PlaylistGridItem(
            item = PlaylistGridItemUiModel(
                title = "Ночной движ",
                details = "28 треков\n1 ч 42 мин",
            ),
            coverState = coverState,
            onClick = {},
            modifier = Modifier.size(150.dp),
        )
    }
}

private val PlaylistBackground = Color(0xFF03040F)
private val PlaylistTitleBackground = Color(0xB30A0714)
private val PlaylistHighlightedTitleBackground = Color(0xB31A0B22)
private val PlaylistDetailsBackground = Color(0x99001622)

/** Агрегирует реальное время выполнения composable плитки без логов на каждый элемент. */
private object PlaylistTileCompositionProfiler {
    private const val SAMPLE_SIZE = 60
    private const val SLOW_COMPOSITION_NANOS = 4_000_000L
    private const val TAG = "PlaylistPerf"

    private var samples = 0
    private var slowSamples = 0
    private var totalNanos = 0L
    private var maxNanos = 0L

    /** Добавляет одно выполнение composable и печатает агрегат после [SAMPLE_SIZE] замеров. */
    fun record(durationNanos: Long) {
        samples += 1
        totalNanos += durationNanos
        maxNanos = maxOf(maxNanos, durationNanos)
        if (durationNanos > SLOW_COMPOSITION_NANOS) slowSamples += 1
        if (samples < SAMPLE_SIZE) return

        val averageTenthsMillis = totalNanos / samples / 100_000L
        val maxTenthsMillis = maxNanos / 100_000L
        Log.d(
            TAG,
            "[composeTileBatch] Замеров=$samples, медленных>4мс=$slowSamples, " +
                "среднее=${formatTenths(averageTenthsMillis)} мс, " +
                "max=${formatTenths(maxTenthsMillis)} мс",
        )
        samples = 0
        slowSamples = 0
        totalNanos = 0L
        maxNanos = 0L
    }

    /** Форматирует десятые доли миллисекунды без тяжёлого форматтера в UI-потоке. */
    private fun formatTenths(value: Long): String = "${value / 10}.${value % 10}"
}
