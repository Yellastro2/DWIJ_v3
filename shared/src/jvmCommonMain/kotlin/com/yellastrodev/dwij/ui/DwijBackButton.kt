package com.yellastrodev.dwij.ui


import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.yellastrodev.dwij.ui.theme.DwijColors


@Composable
fun DwijBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(46.dp)
            .clickable(onClick = onClick),
    ) {
        Canvas(
            modifier = Modifier.size(25.dp),
        ) {
            val stroke = 2.dp.toPx()

            drawLine(
                color = DwijColors.Cyan,
                start = Offset(
                    size.width * 0.72f + 1.2.dp.toPx(),
                    size.height * 0.17f,
                ),
                end = Offset(
                    size.width * 0.29f + 1.2.dp.toPx(),
                    size.height * 0.5f,
                ),
                strokeWidth = stroke,
                cap = StrokeCap.Square,
            )

            drawLine(
                color = DwijColors.Pink,
                start = Offset(
                    size.width * 0.72f - 1.2.dp.toPx(),
                    size.height * 0.83f,
                ),
                end = Offset(
                    size.width * 0.29f - 1.2.dp.toPx(),
                    size.height * 0.5f,
                ),
                strokeWidth = stroke,
                cap = StrokeCap.Square,
            )

            drawLine(
                color = DwijColors.White,
                start = Offset(
                    size.width * 0.72f,
                    size.height * 0.17f,
                ),
                end = Offset(
                    size.width * 0.29f,
                    size.height * 0.5f,
                ),
                strokeWidth = stroke,
                cap = StrokeCap.Square,
            )

            drawLine(
                color = DwijColors.White,
                start = Offset(
                    size.width * 0.29f,
                    size.height * 0.5f,
                ),
                end = Offset(
                    size.width * 0.72f,
                    size.height * 0.83f,
                ),
                strokeWidth = stroke,
                cap = StrokeCap.Square,
            )
        }
    }
}