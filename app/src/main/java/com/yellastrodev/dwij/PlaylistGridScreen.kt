package com.yellastrodev.dwij

import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged

/** Полностью подготовленные для показа данные одной плитки на экране плейлистов. */
@Immutable
data class PlaylistGridScreenItem(
    val id: String,
    val title: String,
    val details: String = "",
    val shouldLoadCover: Boolean = false,
    @DrawableRes val fallbackCoverResId: Int? = null,
    @DrawableRes val artworkResId: Int? = null,
    val highlighted: Boolean = false,
    val isCreateAction: Boolean = false,
)

/**
 * Экран плейлистов с общим переключателем источника и квадратной сеткой в три колонки.
 *
 * Экран не знает о репозиториях и Navigation: все данные и действия приходят параметрами,
 * поэтому его можно отдельно превьюить и постепенно расширять новыми состояниями.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistGridScreen(
    title: String,
    items: List<PlaylistGridScreenItem>,
    selectedSource: HomeMusicSource,
    onSourceSelected: (HomeMusicSource) -> Unit,
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
    val gridState = rememberLazyGridState()
    val screenCoverStates = remember { mutableMapOf<String, PlaylistCoverState>() }
    val scrollFrameSampler = remember { PlaylistScrollFrameSampler() }
    val coverBatchTracker = remember { PlaylistCoverBatchTracker() }
    var requestedSource by remember { mutableStateOf<HomeMusicSource?>(null) }
    var sourceSwitchStartedNanos by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(gridState, scrollFrameSampler) {
        snapshotFlow { gridState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { isScrolling ->
                if (isScrolling) {
                    scrollFrameSampler.start()
                } else {
                    scrollFrameSampler.stop()
                }
            }
    }
    DisposableEffect(scrollFrameSampler) {
        onDispose { scrollFrameSampler.stop() }
    }
    LaunchedEffect(selectedSource, items) {
        val startedNanos = sourceSwitchStartedNanos
        if (startedNanos != null && requestedSource == selectedSource) {
            androidx.compose.runtime.withFrameNanos { }
            Log.d(
                PLAYLIST_PERFORMANCE_TAG,
                "[sourceSwitchFrame] Источник=$selectedSource, элементов=${items.size}, " +
                    "до первого кадра=${elapsedMillis(startedNanos)} мс",
            )
            requestedSource = null
            sourceSwitchStartedNanos = null
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = PlaylistScreenBackground,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .background(PlaylistScreenBackground)
                .statusBarsPadding(),
        ) {
            PlaylistGridHeader(
                title = title,
                onBackClick = onBackClick,
            )
            MusicSourceSelector(
                selectedSource = selectedSource,
                onSourceSelected = { source ->
                    if (source != selectedSource) {
                        requestedSource = source
                        sourceSwitchStartedNanos = SystemClock.elapsedRealtimeNanos()
                        Log.d(
                            PLAYLIST_PERFORMANCE_TAG,
                            "[sourceSwitchClick] Запрошен источник=$source",
                        )
                    }
                    onSourceSelected(source)
                },
                modifier = Modifier.fillMaxWidth(),
            )

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    isLoading -> Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        CircularProgressIndicator(
                            color = PlaylistScreenPink,
                            strokeWidth = 2.dp,
                        )
                    }

                    items.isEmpty() -> Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 32.dp),
                    ) {
                        Text(
                            text = emptyMessage,
                            color = Color(0xFF969BAD),
                            fontSize = 15.sp,
                            lineHeight = 21.sp,
                        )
                    }

                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = gridState,
                        contentPadding = PaddingValues(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .navigationBarsPadding()
                            .padding(horizontal = 6.dp),
                    ) {
                        items(
                            items = items,
                            key = PlaylistGridScreenItem::id,
                            contentType = { item ->
                                when {
                                    item.isCreateAction -> "create"
                                    item.shouldLoadCover -> "remote"
                                    else -> "local"
                                }
                            },
                        ) { item ->
                            LazyPlaylistGridItem(
                                item = item,
                                screenCoverStates = screenCoverStates,
                                loadCover = loadCover,
                                onCoverLoadStarted = coverBatchTracker::onLoadStarted,
                                onCoverLoadFinished = coverBatchTracker::onLoadFinished,
                                onClick = { onItemClick(item) },
                                onLongClick = if (item.isCreateAction) {
                                    null
                                } else {
                                    { onItemLongClick(item) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Получает стабильный holder обложки из экранного кеша и передаёт его плитке, не читая bitmap.
 * Для удалённой обложки запускает отменяемую вместе с LazyGrid-элементом coroutine.
 */
@Composable
private fun LazyPlaylistGridItem(
    item: PlaylistGridScreenItem,
    screenCoverStates: MutableMap<String, PlaylistCoverState>,
    loadCover: suspend (String) -> ImageBitmap?,
    onCoverLoadStarted: () -> Unit,
    onCoverLoadFinished: (Long, CoverLoadResult) -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val coverState = remember(item.id) {
        screenCoverStates.getOrPut(item.id) { PlaylistCoverState() }
    }
    if (item.shouldLoadCover) {
        LoadLazyPlaylistCover(
            itemId = item.id,
            coverState = coverState,
            loadCover = loadCover,
            onCoverLoadStarted = onCoverLoadStarted,
            onCoverLoadFinished = onCoverLoadFinished,
        )
    }

    PlaylistGridItem(
        item = PlaylistGridItemUiModel(
            title = item.title,
            details = item.details,
            fallbackCoverResId = item.fallbackCoverResId,
            artworkResId = item.artworkResId,
            highlighted = item.highlighted,
        ),
        coverState = coverState,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
    )
}

/**
 * Загружает удалённую обложку и записывает её прямо в holder, который читает только
 * внутренний composable изображения. При уходе элемента из LazyGrid coroutine отменяется,
 * а запрос можно повторить.
 */
@Composable
private fun LoadLazyPlaylistCover(
    itemId: String,
    coverState: PlaylistCoverState,
    loadCover: suspend (String) -> ImageBitmap?,
    onCoverLoadStarted: () -> Unit,
    onCoverLoadFinished: (Long, CoverLoadResult) -> Unit,
) {
    val currentLoadCover by rememberUpdatedState(loadCover)
    val currentOnCoverLoadStarted by rememberUpdatedState(onCoverLoadStarted)
    val currentOnCoverLoadFinished by rememberUpdatedState(onCoverLoadFinished)
    LaunchedEffect(itemId, coverState) {
        if (!coverState.tryStartLoading()) return@LaunchedEffect
        val startedNanos = SystemClock.elapsedRealtimeNanos()
        var result = CoverLoadResult.Cancelled
        var requestCompleted = false
        currentOnCoverLoadStarted()
        try {
            val loadedCover = currentLoadCover(itemId)
            coverState.finishLoading(loadedCover)
            requestCompleted = true
            result = if (loadedCover == null) CoverLoadResult.Empty else CoverLoadResult.Loaded
        } finally {
            if (!requestCompleted) coverState.cancelLoading()
            currentOnCoverLoadFinished(
                elapsedMillis(startedNanos),
                result,
            )
        }
    }
}

/** Показывает компактный заголовок экрана и рисует стрелку без отдельного bitmap-ресурса. */
@Composable
private fun PlaylistGridHeader(
    title: String,
    onBackClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(horizontal = 8.dp),
    ) {
        IconButton(onClick = onBackClick) {
            Canvas(modifier = Modifier.size(24.dp)) {
                val stroke = 2.dp.toPx()
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.68f, size.height * 0.18f),
                    end = Offset(size.width * 0.32f, size.height * 0.5f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.32f, size.height * 0.5f),
                    end = Offset(size.width * 0.68f, size.height * 0.82f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = title,
            color = Color.White,
            fontSize = 27.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Показывает обычное заполненное состояние экрана без Fragment и репозиториев. */
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
                "create",
                "Создать плейлист",
                artworkResId = R.drawable.ic_playlist_create,
                isCreateAction = true,
            ),
            PlaylistGridScreenItem(
                "liked",
                "Мне нравится",
                "148 треков\n9 ч 12 мин",
                artworkResId = R.drawable.ic_playlist_liked,
            ),
            PlaylistGridScreenItem("night", "Ночной движ", "28 треков\n1 ч 42 мин"),
            PlaylistGridScreenItem("road", "В дорогу", "41 трек\n2 ч 36 мин"),
            PlaylistGridScreenItem("focus", "Фокус", "19 треков\n58 мин"),
        ),
        selectedSource = HomeMusicSource.Yandex,
        onSourceSelected = {},
        onBackClick = {},
        onItemClick = {},
        onItemLongClick = {},
        emptyMessage = "Плейлистов пока нет",
    )
}

private val PlaylistScreenBackground = Color(0xFF03040F)
private val PlaylistScreenPink = Color(0xFFFF00BF)
private const val PLAYLIST_PERFORMANCE_TAG = "PlaylistPerf"

private enum class CoverLoadResult {
    Loaded,
    Empty,
    Cancelled,
}

/** Собирает одну компактную сводку для параллельной партии видимых обложек. */
private class PlaylistCoverBatchTracker {
    private var batchStartedNanos = 0L
    private var activeLoads = 0
    private var loadedCount = 0
    private var emptyCount = 0
    private var cancelledCount = 0
    private var startedCount = 0
    private var totalLoadMillis = 0L
    private var maxLoadMillis = 0L

    /** Открывает или расширяет текущую параллельную партию загрузки. */
    fun onLoadStarted() {
        if (activeLoads == 0) {
            batchStartedNanos = SystemClock.elapsedRealtimeNanos()
            loadedCount = 0
            emptyCount = 0
            cancelledCount = 0
            startedCount = 0
            totalLoadMillis = 0L
            maxLoadMillis = 0L
        }
        startedCount += 1
        activeLoads += 1
    }

    /** Закрывает одну загрузку и печатает сводку, когда партия полностью закончилась. */
    fun onLoadFinished(durationMillis: Long, result: CoverLoadResult) {
        when (result) {
            CoverLoadResult.Loaded -> loadedCount += 1
            CoverLoadResult.Empty -> emptyCount += 1
            CoverLoadResult.Cancelled -> cancelledCount += 1
        }
        totalLoadMillis += durationMillis
        maxLoadMillis = maxOf(maxLoadMillis, durationMillis)
        activeLoads = (activeLoads - 1).coerceAtLeast(0)
        if (activeLoads == 0) {
            val batchMillis = elapsedMillis(batchStartedNanos)
            val completedCount = loadedCount + emptyCount + cancelledCount
            val averageMillis = if (completedCount == 0) {
                0L
            } else {
                totalLoadMillis / completedCount
            }
            if (startedCount >= 3 || batchMillis >= 20L) {
                Log.d(
                    PLAYLIST_PERFORMANCE_TAG,
                    "[coverBatch] Партия=$batchMillis мс: готово=$loadedCount, " +
                        "пусто=$emptyCount, отменено=$cancelledCount, " +
                        "среднее=$averageMillis мс, max=$maxLoadMillis мс",
                )
            }
        }
    }
}

/** Считает интервалы реальных кадров только пока LazyGrid прокручивается. */
private class PlaylistScrollFrameSampler : Choreographer.FrameCallback {
    private val frameIntervalsMillis = mutableListOf<Long>()
    private var previousFrameNanos = 0L
    private var scrollStartedNanos = 0L
    private var isRunning = false

    /** Начинает новую независимую выборку кадров прокрутки. */
    fun start() {
        if (isRunning) return
        isRunning = true
        previousFrameNanos = 0L
        scrollStartedNanos = SystemClock.elapsedRealtimeNanos()
        frameIntervalsMillis.clear()
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** Завершает выборку и выводит перцентили интервалов между кадрами. */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        Choreographer.getInstance().removeFrameCallback(this)
        if (frameIntervalsMillis.isEmpty()) return

        val sorted = frameIntervalsMillis.sorted()
        val slowFrames = sorted.count { it > 24L }
        Log.d(
            PLAYLIST_PERFORMANCE_TAG,
            "[scrollFrames] Прокрутка=${elapsedMillis(scrollStartedNanos)} мс, " +
                "кадров=${sorted.size}, >24мс=$slowFrames, " +
                "p50=${percentile(sorted, 0.50f)} мс, " +
                "p90=${percentile(sorted, 0.90f)} мс, max=${sorted.last()} мс",
        )
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning) return
        if (previousFrameNanos != 0L) {
            frameIntervalsMillis += (frameTimeNanos - previousFrameNanos) / 1_000_000L
        }
        previousFrameNanos = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** Возвращает приближённый перцентиль небольшого уже отсортированного набора. */
    private fun percentile(sorted: List<Long>, fraction: Float): Long {
        val index = ((sorted.lastIndex * fraction).toInt()).coerceIn(sorted.indices)
        return sorted[index]
    }
}

/** Преобразует монотонный timestamp в прошедшие миллисекунды. */
private fun elapsedMillis(startedNanos: Long): Long =
    (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000L
