package com.yellastrodev.dwij

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
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

/** Полностью подготовленные для показа данные одной плитки на экране плейлистов. */
data class PlaylistGridScreenItem(
    val id: String,
    val title: String,
    val details: String = "",
    val cover: ImageBitmap? = null,
    @DrawableRes val iconRes: Int? = null,
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
    emptyMessage: String,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
) {
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
                onSourceSelected = onSourceSelected,
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
                        ) { item ->
                            PlaylistGridItem(
                                item = PlaylistGridItemUiModel(
                                    title = item.title,
                                details = item.details,
                                cover = item.cover,
                                iconRes = item.iconRes,
                                    highlighted = item.highlighted,
                                ),
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
                iconRes = R.drawable.ic_playlist_create,
                isCreateAction = true,
            ),
            PlaylistGridScreenItem(
                "liked",
                "Мне нравится",
                "148 треков\n9 ч 12 мин",
                iconRes = R.drawable.ic_playlist_liked,
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
