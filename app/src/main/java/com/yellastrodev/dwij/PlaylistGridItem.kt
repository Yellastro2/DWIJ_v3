package com.yellastrodev.dwij

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Данные одной квадратной плитки плейлиста, не зависящие от экрана и его стейтов. */
data class PlaylistGridItemUiModel(
    val title: String,
    val details: String = "",
    val cover: ImageBitmap? = null,
    @DrawableRes val iconRes: Int? = null,
    val highlighted: Boolean = false,
)

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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(12.dp)
    val accent = if (item.highlighted) PlaylistCyan else PlaylistPink

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        if (item.cover != null) {
            Image(
                bitmap = item.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Image(
                painter = painterResource(R.drawable.back1_1000),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color(0x1903040F),
                            0.48f to Color(0x3303040F),
                            1f to Color(0xCC03040F),
                        ),
                    ),
                ),
        )

        item.iconRes?.let { iconRes ->
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(7.dp)
                    .size(54.dp)
                    .alpha(0.92f),
            )
        }

        PlaylistGridGlitchFrame(
            accent = accent,
            highlighted = item.highlighted,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 6.dp, top = 6.dp, end = 10.dp),
        ) {
            PlaylistGridTextPlate(accent = accent) {
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
                PlaylistGridTextPlate(accent = PlaylistCyan, quiet = true) {
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
    }
}

/** Рисует тонкую рамку плитки и несколько коротких цветных разрывов по её периметру. */
@Composable
private fun PlaylistGridGlitchFrame(
    accent: Color,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val inset = 1.dp.toPx()
        val strokeWidth = if (highlighted) 1.6.dp.toPx() else 0.8.dp.toPx()
        val radius = 11.dp.toPx()

        drawRoundRect(
            color = if (highlighted) accent.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.2f),
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2f, size.height - inset * 2f),
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = strokeWidth),
        )
        drawLine(
            color = PlaylistPink.copy(alpha = 0.9f),
            start = Offset(size.width * 0.12f, inset),
            end = Offset(size.width * 0.43f, inset),
            strokeWidth = 1.5.dp.toPx(),
        )
        drawLine(
            color = PlaylistCyan.copy(alpha = 0.9f),
            start = Offset(size.width * 0.58f, size.height - inset),
            end = Offset(size.width * 0.88f, size.height - inset),
            strokeWidth = 1.5.dp.toPx(),
        )
    }
}

/** Создаёт тёмную читаемую подложку, оставляя неоновый цвет только на тонких кромках. */
@Composable
private fun PlaylistGridTextPlate(
    accent: Color,
    quiet: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xF20A0714),
                        Color(0xD90A0714),
                        accent.copy(alpha = if (quiet) 0.24f else 0.36f),
                    ),
                ),
                shape = RoundedCornerShape(
                    topStart = 2.dp,
                    topEnd = 7.dp,
                    bottomEnd = 7.dp,
                    bottomStart = 2.dp,
                ),
            )
            .drawBehind {
                drawRect(
                    color = accent.copy(alpha = if (quiet) 0.65f else 0.95f),
                    size = Size(2.dp.toPx(), size.height),
                )
                drawLine(
                    color = PlaylistCyan.copy(alpha = 0.75f),
                    start = Offset(size.width * 0.12f, 0f),
                    end = Offset(size.width * 0.58f, 0f),
                    strokeWidth = 1.dp.toPx(),
                )
                drawLine(
                    color = PlaylistPink.copy(alpha = 0.72f),
                    start = Offset(size.width * 0.52f, size.height),
                    end = Offset(size.width * 0.88f, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            },
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
            onClick = {},
            modifier = Modifier.size(150.dp),
        )
    }
}

private val PlaylistBackground = Color(0xFF03040F)
private val PlaylistPink = Color(0xFFFF00BF)
private val PlaylistCyan = Color(0xFF00BBEB)
