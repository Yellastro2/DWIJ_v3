package com.yellastrodev.dwij

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Рисует постоянный индикатор из трёх сцепленных знаков Play для песни с дублями. */
@Composable
internal fun MultipleSourcesIndicator(
    modifier: Modifier = Modifier,
    semanticDescription: String? = null,
) {
    val description = semanticDescription ?: stringResource(
        R.string.player_multiple_sources_active_content_description,
    )
    Canvas(
        modifier = modifier.semantics {
            contentDescription = description
        },
    ) {
        val triangleWidth = size.width * 0.34f
        val triangleHeight = size.height * 0.52f
        val startX = size.width * 0.14f
        val stepX = size.width * 0.18f
        val centerY = size.height * 0.5f
        val colors = listOf(MultipleSourcesCyan, MultipleSourcesPink, Color.White)

        repeat(3) { index ->
            val x = startX + stepX * index
            val yOffset = when (index) {
                0 -> size.height * 0.04f
                1 -> -size.height * 0.04f
                else -> 0f
            }
            val path = Path().apply {
                moveTo(x, centerY - triangleHeight / 2f + yOffset)
                lineTo(x + triangleWidth, centerY + yOffset)
                lineTo(x, centerY + triangleHeight / 2f + yOffset)
                close()
            }
            drawPath(
                path = path,
                color = colors[index].copy(alpha = 0.18f),
            )
            drawPath(
                path = path,
                color = colors[index],
                style = Stroke(width = 1.25.dp.toPx()),
            )
        }
    }
}

private val MultipleSourcesPink = Color(0xFFFF00BF)
private val MultipleSourcesCyan = Color(0xFF00BBEB)
