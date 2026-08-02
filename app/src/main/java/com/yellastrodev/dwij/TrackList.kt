package com.yellastrodev.dwij

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException

/**
 * Независимые от источника данные одной строки трека.
 *
 * [isYandexUnavailable] включает индикатор, а [isPlaybackBlocked] дополнительно приглушает
 * метаданные и сообщает владельцу списка, что вместо воспроизведения нужно объяснить отказ.
 */
@Immutable
data class TrackListItemUiModel(
    val key: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val shouldLoadCover: Boolean = true,
    val isYandexUnavailable: Boolean = false,
    val isPlaybackBlocked: Boolean = false,
)

/**
 * Линейный ленивый список треков для плейлистов, каталога и локальной медиатеки.
 *
 * Источник данных и воспроизведение остаются снаружи. Список запрашивает обложки только для
 * скомпонованных строк и сохраняет их по уникальному ключу строки на время жизни экрана.
 */
@Composable
fun TrackList(
    items: List<TrackListItemUiModel>,
    onItemClick: (index: Int, item: TrackListItemUiModel) -> Unit,
    loadCover: suspend (trackId: String) -> ImageBitmap? = { null },
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    emptyMessage: String = "",
    header: (@Composable () -> Unit)? = null,
) {
    val coverStates = remember { mutableMapOf<String, TrackCoverState>() }
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(vertical = 4.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        header?.let { headerContent ->
            item(
                key = "track_list_header",
                contentType = "header",
            ) {
                headerContent()
            }
        }
        if (items.isEmpty()) {
            item(
                key = "track_list_empty",
                contentType = "empty",
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                ) {
                    if (emptyMessage.isNotBlank()) {
                        Text(
                            text = emptyMessage,
                            color = TrackSecondaryText,
                            fontSize = 15.sp,
                        )
                    }
                }
            }
        } else {
            itemsIndexed(
                items = items,
                key = { _, item -> item.key },
                contentType = { _, item -> if (item.shouldLoadCover) "cover" else "plain" },
            ) { index, item ->
                val coverState = remember(item.key) {
                    coverStates.getOrPut(item.key) { TrackCoverState() }
                }
                if (item.shouldLoadCover) {
                    TrackCoverLoader(
                        trackId = item.trackId,
                        coverState = coverState,
                        loadCover = loadCover,
                    )
                }
                TrackListItem(
                    item = item,
                    coverState = coverState,
                    onClick = { onItemClick(index, item) },
                )
            }
        }
    }
}

/** Рисует строку трека и визуально отделяет недоступное без кэша состояние. */
@Composable
fun TrackListItem(
    item: TrackListItemUiModel,
    coverState: TrackCoverState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val titleColor = if (item.isPlaybackBlocked) {
        Color.White.copy(alpha = 0.34f)
    } else {
        Color.White
    }
    val artistColor = if (item.isPlaybackBlocked) {
        TrackSecondaryText.copy(alpha = 0.38f)
    } else {
        TrackSecondaryText
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable(onClick = onClick),
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
                        end = if (item.isYandexUnavailable) 30.dp else 8.dp,
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
        if (item.isYandexUnavailable) {
            TrackUnavailableIndicator(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 5.dp, end = 8.dp),
            )
        }
    }
}

/** Рисует маленькую жёлтую перечёркнутую букву «Я» для недоступного Яндекс-трека. */
@Composable
private fun TrackUnavailableIndicator(modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(22.dp),
    ) {
        Text(
            text = stringResource(R.string.track_unavailable_indicator),
            color = TrackUnavailableYellow,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            val inset = 3.dp.toPx()
            drawLine(
                color = TrackUnavailableYellow,
                start = Offset(inset, size.height - inset),
                end = Offset(size.width - inset, inset),
                strokeWidth = 1.6.dp.toPx(),
                cap = StrokeCap.Square,
            )
        }
    }
}

/**
 * Отдельное стабильное состояние обложки: его изменение перезапускает только [TrackCover].
 */
@Stable
class TrackCoverState(initialBitmap: ImageBitmap? = null) {
    var bitmap by mutableStateOf(initialBitmap)
        private set

    private var isLoading = false

    /** Не допускает параллельных запросов одной и той же обложки. */
    internal fun tryStartLoading(): Boolean {
        if (bitmap != null || isLoading) return false
        isLoading = true
        return true
    }

    /** Публикует загруженную обложку и разрешает последующие запросы после пустого результата. */
    internal fun finishLoading(loadedBitmap: ImageBitmap?) {
        bitmap = loadedBitmap
        isLoading = false
    }

    /** Разрешает повторить запрос, который был отменён вместе с ушедшей строкой. */
    internal fun cancelLoading() {
        isLoading = false
    }
}

/** Загружает обложку в жизненном цикле конкретной скомпонованной строки. */
@Composable
private fun TrackCoverLoader(
    trackId: String,
    coverState: TrackCoverState,
    loadCover: suspend (trackId: String) -> ImageBitmap?,
) {
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
                Log.w(
                    TRACK_LIST_TAG,
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
        modifier = modifier.background(TrackCoverBackground),
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
            drawNote(Color(0xFF00C8F0), cyanOffset)
            drawNote(Color(0xFFFF1694), -cyanOffset)
            drawNote(Color.White, 0f)
        }
    }
}

/** Показывает несколько состояний нового элемента без Fragment и репозиториев. */
@Preview(
    name = "Track list",
    widthDp = 360,
    heightDp = 360,
    showBackground = true,
    backgroundColor = 0xFF03040F,
)
@Composable
private fun TrackListPreview() {
    TrackList(
        items = listOf(
            TrackListItemUiModel("1:0", "1", "Ночной город", "Три дня дождя", false),
            TrackListItemUiModel(
                "2:0",
                "2",
                "MARDI GRAS",
                "Scriptz",
                false,
                isYandexUnavailable = true,
            ),
            TrackListItemUiModel(
                "3:0",
                "3",
                "FROSTSURGE",
                "qõke, N:GHT",
                false,
                isYandexUnavailable = true,
                isPlaybackBlocked = true,
            ),
        ),
        onItemClick = { _, _ -> },
        modifier = Modifier.background(TrackListBackground),
    )
}

private val TrackListBackground = Color(0xFF03040F)
private val TrackCoverBackground = Color(0xFF101522)
private val TrackSecondaryText = Color(0xFFA7AABC)
private val TrackUnavailableYellow = Color(0xFFFFD54A)
private const val TRACK_LIST_TAG = "TrackList"
