package com.yellastrodev.dwij.ui.playlist

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yellastrodev.dwij.HomeMusicSource
import com.yellastrodev.dwij.ui.theme.DwijColors
import androidx.compose.runtime.getValue

/** Минимальный общий контракт элемента сетки плейлистов. */
interface PlaylistGridEntry {
    val id: String
    val isCreateAction: Boolean
    val shouldLoadCover: Boolean
}

/**
 * Общий экран сетки плейлистов.
 *
 * Не знает о:
 * - Android drawable resources;
 * - строковых ресурсах;
 * - Navigation;
 * - Context;
 * - репозиториях;
 * - Android performance API.
 *
 * Платформенный слой передаёт отрисовку переключателя
 * источника и конкретной плитки через Compose slots.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T : PlaylistGridEntry> PlaylistGridScreen(
    title: String,
    items: List<T>,
    selectedSource: HomeMusicSource,
    onSourceSelected: (HomeMusicSource) -> Unit,
    onBackClick: () -> Unit,
    onItemClick: (T) -> Unit,
    onItemLongClick: (T) -> Unit,
    emptyMessage: String,
    loadingMessage: String,
    sourceSelector: @Composable (
        selectedSource: HomeMusicSource,
        onSourceSelected: (HomeMusicSource) -> Unit,
    ) -> Unit,
    itemContent: @Composable (
        item: T,
        coverState: PlaylistCoverState,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)?,
        modifier: Modifier,
    ) -> Unit,
    loadCover: suspend (String) -> ImageBitmap? = {
        null
    },
    showSourceSelector: Boolean = true,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    val gridState = rememberLazyGridState()

    val screenCoverStates = remember {
        mutableMapOf<String, PlaylistCoverState>()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DwijColors.Background,
        contentWindowInsets = WindowInsets(
            left = 0,
            top = 0,
            right = 0,
            bottom = 0,
        ),
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .background(DwijColors.Background)
                .statusBarsPadding(),
        ) {
            PlaylistGridHeader(
                title = title,
                onBackClick = onBackClick,
            )

            if (showSourceSelector) {
                sourceSelector(
                    selectedSource,
                    onSourceSelected,
                )
            }

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    isLoading &&
                            items.none { item ->
                                !item.isCreateAction
                            } -> {
                        PlaylistGridLoadingPlaceholder(
                            loadingMessage = loadingMessage,
                        )
                    }

                    items.isEmpty() -> {
                        PlaylistGridEmptyState(
                            message = emptyMessage,
                        )
                    }

                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            state = gridState,
                            contentPadding = PaddingValues(
                                vertical = 6.dp,
                            ),
                            horizontalArrangement =
                                Arrangement.spacedBy(6.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .navigationBarsPadding()
                                .padding(horizontal = 6.dp),
                        ) {
                            items(
                                items = items,
                                key = { item -> item.id },
                                contentType = { item ->
                                    when {
                                        item.isCreateAction ->
                                            ITEM_TYPE_CREATE

                                        item.shouldLoadCover ->
                                            ITEM_TYPE_REMOTE

                                        else ->
                                            ITEM_TYPE_LOCAL
                                    }
                                },
                            ) { item ->
                                SharedLazyPlaylistGridItem(
                                    item = item,
                                    itemId = item.id,
                                    isCreateAction =
                                        item.isCreateAction,
                                    shouldLoadCover =
                                        item.shouldLoadCover,
                                    screenCoverStates =
                                        screenCoverStates,
                                    loadCover = loadCover,
                                    onClick = {
                                        onItemClick(item)
                                    },
                                    onLongClick = {
                                        onItemLongClick(item)
                                    },
                                    itemContent = itemContent,
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Связывает общий экранный кеш обложек
 * с одной конкретной плиткой.
 */
@Composable
private fun <T> SharedLazyPlaylistGridItem(
    item: T,
    itemId: String,
    isCreateAction: Boolean,
    shouldLoadCover: Boolean,
    screenCoverStates:
    MutableMap<String, PlaylistCoverState>,
    loadCover: suspend (String) -> ImageBitmap?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    itemContent: @Composable (
        item: T,
        coverState: PlaylistCoverState,
        onClick: () -> Unit,
        onLongClick: (() -> Unit)?,
        modifier: Modifier,
    ) -> Unit,
    modifier: Modifier = Modifier,
) {
    val coverState = remember(itemId) {
        screenCoverStates.getOrPut(itemId) {
            PlaylistCoverState()
        }
    }

    if (shouldLoadCover) {
        LoadPlaylistCover(
            itemId = itemId,
            coverState = coverState,
            loadCover = loadCover,
        )
    }

    itemContent(
        item,
        coverState,
        onClick,
        if (isCreateAction) {
            null
        } else {
            onLongClick
        },
        modifier,
    )
}

/**
 * Загружает обложку только для видимой LazyGrid-плитки.
 *
 * При уходе элемента из композиции coroutine отменяется.
 */
@Composable
private fun LoadPlaylistCover(
    itemId: String,
    coverState: PlaylistCoverState,
    loadCover: suspend (String) -> ImageBitmap?,
) {
    val currentLoadCover by rememberUpdatedState(
        loadCover,
    )

    LaunchedEffect(
        itemId,
        coverState,
    ) {
        if (!coverState.tryStartLoading()) {
            return@LaunchedEffect
        }

        var requestCompleted = false

        try {
            val loadedCover =
                currentLoadCover(itemId)

            coverState.finishLoading(
                loadedCover,
            )

            requestCompleted = true
        } finally {
            if (!requestCompleted) {
                coverState.cancelLoading()
            }
        }
    }
}

/**
 * Пустое состояние экрана.
 */
@Composable
private fun PlaylistGridEmptyState(
    message: String,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
    ) {
        Text(
            text = message,
            color = DwijColors.ListSecondaryText,
            fontSize = 15.sp,
            lineHeight = 21.sp,
        )
    }
}

/**
 * Бледные плитки той же геометрии,
 * что и готовая сетка.
 */
@Composable
private fun PlaylistGridLoadingPlaceholder(
    loadingMessage: String,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(
            vertical = 6.dp,
        ),
        horizontalArrangement =
            Arrangement.spacedBy(6.dp),
        verticalArrangement =
            Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 6.dp),
    ) {
        items(
            count =
                PLAYLIST_LOADING_PLACEHOLDER_COUNT,
            key = { index ->
                "playlist_loading_$index"
            },
        ) { index ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(
                        RoundedCornerShape(12.dp),
                    )
                    .background(
                        DwijColors.LoadingPlaceholder.copy(alpha = 0.62f),
                    ),
            ) {
                if (index == 0) {
                    Text(
                        text = loadingMessage,
                        color = DwijColors.White.copy(
                            alpha = 0.52f,
                        ),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(
                            10.dp,
                        ),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .padding(10.dp)
                            .fillMaxWidth(0.72f)
                            .height(12.dp)
                            .clip(
                                RoundedCornerShape(
                                    4.dp,
                                ),
                            )
                            .background(
                                DwijColors.White.copy(
                                    alpha = 0.09f,
                                ),
                            ),
                    )
                }
            }
        }
    }
}

/**
 * Заголовок экрана.
 *
 * Стрелка рисуется Compose Canvas и не требует
 * платформенного изображения.
 */
@Composable
private fun PlaylistGridHeader(
    title: String,
    onBackClick: () -> Unit,
) {
    Row(
        verticalAlignment =
            Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(horizontal = 8.dp),
    ) {
        IconButton(
            onClick = onBackClick,
        ) {
            Canvas(
                modifier = Modifier.size(24.dp),
            ) {
                val stroke =
                    2.dp.toPx()

                drawLine(
                    color = DwijColors.White,
                    start = Offset(
                        x = size.width * 0.68f,
                        y = size.height * 0.18f,
                    ),
                    end = Offset(
                        x = size.width * 0.32f,
                        y = size.height * 0.5f,
                    ),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )

                drawLine(
                    color = DwijColors.White,
                    start = Offset(
                        x = size.width * 0.32f,
                        y = size.height * 0.5f,
                    ),
                    end = Offset(
                        x = size.width * 0.68f,
                        y = size.height * 0.82f,
                    ),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
            }
        }

        Spacer(
            modifier = Modifier.width(4.dp),
        )

        Text(
            text = title,
            color = DwijColors.White,
            fontSize = 27.sp,
            fontWeight =
                FontWeight.SemiBold,
            maxLines = 1,
            overflow =
                TextOverflow.Ellipsis,
        )
    }
}


private const val
        PLAYLIST_LOADING_PLACEHOLDER_COUNT = 6

private const val ITEM_TYPE_CREATE =
    "create"

private const val ITEM_TYPE_REMOTE =
    "remote"

private const val ITEM_TYPE_LOCAL =
    "local"
