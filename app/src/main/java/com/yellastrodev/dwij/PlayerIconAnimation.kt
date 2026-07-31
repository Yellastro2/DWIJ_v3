package com.yellastrodev.dwij

import android.util.Log
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val PLAYER_ICON_ANIMATION_TAG = "PlayerIconAnimation"

/**
 * Compose-кнопка плеера с бесконечной глитч-анимацией.
 *
 * Анимация задаётся через [rememberInfiniteTransition], поэтому не зависит от
 * AnimatedVectorDrawable и останавливается вместе с Composition.
 */
@Composable
fun PlayerIconButton(
    modifier: Modifier = Modifier,
    showPreviewGlitch: Boolean = false,
    onClick: () -> Unit,
) {
    val glitchTransition = rememberInfiniteTransition(label = "playerGlitch")
    val glitchPhase by glitchTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1600,
                easing = LinearEasing,
            ),
        ),
        label = "glitchPhase",
    )

    IconButton(
        modifier = modifier,
        onClick = onClick,
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val iconSize = minOf(maxWidth, maxHeight)
            val playerIcon = painterResource(R.drawable.ic_player)
            val animatedGlitchShift = calculateGlitchShift(glitchPhase)
            val effectiveRightGlitchShift = if (showPreviewGlitch) 0.06f else animatedGlitchShift
            val effectiveLeftGlitchShift = if (showPreviewGlitch) -0.06f else -animatedGlitchShift
            val rightGlitchDp = iconSize * effectiveRightGlitchShift
            val leftGlitchDp = iconSize * effectiveLeftGlitchShift
            val lastLoggedShift = remember { floatArrayOf(Float.NaN) }

            SideEffect {
                if (lastLoggedShift[0] != effectiveRightGlitchShift) {
                    Log.d(
                        PLAYER_ICON_ANIMATION_TAG,
                        "[PlayerIconButton] Сдвиг глитч-полос: " +
                            "phase=$glitchPhase, " +
                            "animationShift=$effectiveRightGlitchShift, " +
                            "iconSize=${iconSize.value}dp, " +
                            "верхняя/нижняя=${rightGlitchDp.value}dp, " +
                            "центральная=${leftGlitchDp.value}dp"
                    )
                    lastLoggedShift[0] = effectiveRightGlitchShift
                }
            }

            Image(
                painter = playerIcon,
                contentDescription = stringResource(R.string.player_button_content_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(iconSize),
            )
            PlayerGlitchStripe(
                painter = playerIcon,
                imageSize = iconSize,
                topOffset = iconSize * 0.14f,
                stripeHeight = iconSize * 0.06f,
                horizontalShift = rightGlitchDp,
            )
            PlayerGlitchStripe(
                painter = playerIcon,
                imageSize = iconSize,
                topOffset = iconSize * 0.37f,
                stripeHeight = iconSize * 0.07f,
                horizontalShift = leftGlitchDp,
            )
            PlayerGlitchStripe(
                painter = playerIcon,
                imageSize = iconSize,
                topOffset = iconSize * 0.64f,
                stripeHeight = iconSize * 0.06f,
                horizontalShift = rightGlitchDp,
            )
        }
    }
}

/** Превращает равномерную фазу 0..1 в две короткие серии глитч-рывков. */
private fun calculateGlitchShift(phase: Float): Float = when {
    phase < 0.45f -> 0f
    phase < 0.50f -> interpolate(phase, 0.45f, 0.50f, 0f, 0.06f)
    phase < 0.54f -> interpolate(phase, 0.50f, 0.54f, 0.06f, -0.035f)
    phase < 0.58f -> interpolate(phase, 0.54f, 0.58f, -0.035f, 0.045f)
    phase < 0.63f -> interpolate(phase, 0.58f, 0.63f, 0.045f, 0f)
    phase < 0.82f -> 0f
    phase < 0.85f -> interpolate(phase, 0.82f, 0.85f, 0f, 0.03f)
    phase < 0.88f -> interpolate(phase, 0.85f, 0.88f, 0.03f, -0.02f)
    phase < 0.91f -> interpolate(phase, 0.88f, 0.91f, -0.02f, 0f)
    else -> 0f
}

/** Линейно переводит текущую фазу участка в значение смещения. */
private fun interpolate(
    phase: Float,
    phaseStart: Float,
    phaseEnd: Float,
    valueStart: Float,
    valueEnd: Float,
): Float {
    val fraction = (phase - phaseStart) / (phaseEnd - phaseStart)
    return valueStart + (valueEnd - valueStart) * fraction
}

/** Рисует одну обрезанную глитч-полосу поверх основной иконки. */
@Composable
private fun BoxScope.PlayerGlitchStripe(
    painter: Painter,
    imageSize: Dp,
    topOffset: Dp,
    stripeHeight: Dp,
    horizontalShift: Dp,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(stripeHeight)
            .align(Alignment.TopCenter)
            .offset(y = topOffset)
            .clipToBounds(),
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .wrapContentSize(
                    align = Alignment.TopCenter,
                    unbounded = true,
                )
                .offset(x = horizontalShift, y = -topOffset)
                .requiredSize(imageSize),
        )
    }
}

/** Проигрывает анимацию кнопки в Android Studio Interactive Preview. */
@Preview(name = "Animated — Interactive Mode", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PlayerIconButtonPreview() {
    Box(modifier = Modifier.size(512.dp)) {
        PlayerIconButton(modifier = Modifier.fillMaxSize(), onClick = {})
    }
}

/** Показывает заметный глитч-кадр в обычном статическом Preview. */
@Preview(name = "Static glitch frame", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PlayerIconButtonGlitchFramePreview() {
    Box(modifier = Modifier.size(512.dp)) {
        PlayerIconButton(
            modifier = Modifier.fillMaxSize(),
            showPreviewGlitch = true,
            onClick = {},
        )
    }
}
