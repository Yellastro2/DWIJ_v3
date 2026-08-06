package com.yellastrodev.dwij.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import com.yellastrodev.dwij.TrackListItemUiModel
import com.yellastrodev.dwij.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Универсальный экран музыкального объекта: общая шапка и переданный список треков.
 *
 * Подходит плейлисту, альбому, исполнителю или абстрактной подборке: route передаёт только
 * заголовок, описание, обложку, доступные действия и подготовленные элементы списка.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObjectScreen(
    title: String,
    subtitle: String,
    description: String?,
    cover: ImageBitmap?,
    tracks: List<TrackListItemUiModel>,
    onBackClick: () -> Unit,
    onPlayClick: () -> Unit,
    onTrackClick: (index: Int, item: TrackListItemUiModel) -> Unit,
    trackContextMenuContent: (@Composable (
        index: Int,
        item: TrackListItemUiModel,
        onDismiss: () -> Unit,
    ) -> Unit)? = null,
    loadTrackCover: suspend (trackId: String) -> ImageBitmap?,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    showShare: Boolean = true,
    showWave: Boolean = true,
    onShareClick: () -> Unit = {},
    onWaveClick: () -> Unit = {},
    emptyMessage: String = "",
    isLoading: Boolean = false,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObjectScreenBackground)
            .navigationBarsPadding(),
    ) {
        ObjectTopBar(
            title = title,
            listState = listState,
            onBackClick = onBackClick,
        )
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f),
        ) {
            TrackList(
                items = tracks,
                state = listState,
                loadCover = loadTrackCover,
                onItemClick = onTrackClick,
                contextMenuContent = trackContextMenuContent,
                emptyMessage = emptyMessage,
                isLoading = isLoading,
                header = {
                    ObjectHeader(
                        title = title,
                        subtitle = subtitle,
                        description = description,
                        cover = cover,
                        showShare = showShare,
                        showWave = showWave,
                        onShareClick = onShareClick,
                        onPlayClick = onPlayClick,
                        onWaveClick = onWaveClick,
                    )
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Рисует закреплённую верхнюю строку с кнопкой назад и текущим названием. */
@Composable
private fun ObjectTopBar(
    title: String,
    listState: LazyListState,
    onBackClick: () -> Unit,
) {
    val collapseDistancePx = with(LocalDensity.current) { 320.dp.toPx() }
    val collapseFraction by remember(listState, collapseDistancePx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset / collapseDistancePx).coerceIn(0f, 1f)
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(ObjectScreenBackground.copy(alpha = collapseFraction * 0.96f))
            .statusBarsPadding()
            .height(54.dp)
            .padding(horizontal = 6.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(46.dp)
                .clickable(onClick = onBackClick),
        ) {
            Canvas(modifier = Modifier.size(25.dp)) {
                val stroke = 2.dp.toPx()
                drawLine(
                    color = ObjectCyan,
                    start = Offset(size.width * 0.72f + 1.2.dp.toPx(), size.height * 0.17f),
                    end = Offset(size.width * 0.29f + 1.2.dp.toPx(), size.height * 0.5f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
                drawLine(
                    color = ObjectPink,
                    start = Offset(size.width * 0.72f - 1.2.dp.toPx(), size.height * 0.83f),
                    end = Offset(size.width * 0.29f - 1.2.dp.toPx(), size.height * 0.5f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.72f, size.height * 0.17f),
                    end = Offset(size.width * 0.29f, size.height * 0.5f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.29f, size.height * 0.5f),
                    end = Offset(size.width * 0.72f, size.height * 0.83f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
            }
        }
        Text(
            text = title,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 58.dp)
                .graphicsLayer {
                    alpha = ((collapseFraction - 0.78f) / 0.22f).coerceIn(0f, 1f)
                },
        )
    }
}

/** Рисует неоновую обложку, метаданные и действия объекта перед первым треком. */
@Composable
private fun ObjectHeader(
    title: String,
    subtitle: String,
    description: String?,
    cover: ImageBitmap?,
    showShare: Boolean,
    showWave: Boolean,
    onShareClick: () -> Unit,
    onPlayClick: () -> Unit,
    onWaveClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        Image(
            painter = painterResource(Res.drawable.bg_player_glitch_v2),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = 0.34f,
            modifier = Modifier
                .fillMaxWidth()
                .height(330.dp)
                .align(Alignment.TopCenter),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            ObjectCover(
                cover = cover,
                modifier = Modifier.size(190.dp),
            )
            Text(
                text = title,
                color = Color.White,
                fontSize = 29.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = subtitle,
                color = ObjectSecondaryText,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 7.dp),
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    color = Color(0xFFD4D6E0),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 9.dp, start = 18.dp, end = 18.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 17.dp),
            ) {
                if (showShare) {
                    ObjectActionButton(
                        text = stringResource(Res.string.object_share),
                        accent = ObjectCyan,
                        onClick = onShareClick,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                ObjectPlayButton(onClick = onPlayClick)
                if (showWave) {
                    Spacer(modifier = Modifier.width(12.dp))
                    ObjectActionButton(
                        text = stringResource(Res.string.object_wave),
                        accent = ObjectPink,
                        onClick = onWaveClick,
                    )
                }
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp)
                    .height(2.dp),
            ) {
                drawLine(
                    color = ObjectCyan.copy(alpha = 0.7f),
                    start = Offset(size.width * 0.05f, size.height * 0.25f),
                    end = Offset(size.width * 0.48f, size.height * 0.25f),
                    strokeWidth = 1.dp.toPx(),
                )
                drawLine(
                    color = ObjectPink.copy(alpha = 0.75f),
                    start = Offset(size.width * 0.52f, size.height * 0.75f),
                    end = Offset(size.width * 0.95f, size.height * 0.75f),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }
    }
}

/** Показывает обложку с готовым PNG-контуром или фирменную заглушку подборки. */
@Composable
private fun ObjectCover(
    cover: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier,
    ) {
        if (cover != null) {
            Image(
                bitmap = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(174.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(174.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC0D1020)),
            ) {
                Image(
                    painter = painterResource(Res.drawable.ic_playlist_liked),
                    contentDescription = null,
                    modifier = Modifier.size(92.dp),
                )
            }
        }
        Image(
            painter = painterResource(Res.drawable.dvizh_album_thumb_glitch_frame_contour),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Использует наш сгенерированный PNG play без Material-иконки и текстовой плашки. */
@Composable
private fun ObjectPlayButton(onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(76.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xB30A0714))
            .border(1.dp, ObjectPink.copy(alpha = 0.85f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
    ) {
        Image(
            painter = painterResource(Res.drawable.ic_player_play_v2),
            contentDescription = stringResource(Res.string.object_play_content_description),
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.requiredSize(width = 225.dp, height = 150.dp),
        )
    }
}

/** Рисует небольшое вторичное действие без тяжёлого фонового ресурса. */
@Composable
private fun ObjectActionButton(
    text: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(100.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xA6080B16))
            .border(1.dp, accent.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** Показывает объект и список без навигационного хоста, базы и сетевых обложек. */
@Preview(
    name = "Object screen",
    widthDp = 360,
    heightDp = 780,
    showBackground = true,
    backgroundColor = 0xFF03040F,
)
@Composable
private fun ObjectScreenPreview() {
    ObjectScreen(
        title = "Ночной движ",
        subtitle = "28 треков",
        description = "Музыка для ночной дороги и пустого города",
        cover = null,
        tracks = listOf(
            TrackListItemUiModel("1:0", "1", "Ночной город", "Три дня дождя", false),
            TrackListItemUiModel("2:0", "2", "MARDI GRAS", "Scriptz", false),
            TrackListItemUiModel("3:0", "3", "FROSTSURGE", "qõke, N:GHT", false),
        ),
        onBackClick = {},
        onPlayClick = {},
        onTrackClick = { _, _ -> },
        loadTrackCover = { null },
    )
}

private val ObjectScreenBackground = Color(0xFF03040F)
private val ObjectSecondaryText = Color(0xFFA7AABC)
private val ObjectPink = Color(0xFFFF00BF)
private val ObjectCyan = Color(0xFF00BBEB)
