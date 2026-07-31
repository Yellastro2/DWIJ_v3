package com.yellastrodev.dwij

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.random.Random

private const val GLITCH_CYCLE_DURATION_MILLIS = 60_000
private const val GLITCH_SEQUENCE_END_MARGIN_MILLIS = 1_000
private val GROUP_A_STRIPE_OFFSETS = listOf(0.12f, 0.29f, 0.46f, 0.63f, 0.80f)
private val GROUP_B_STRIPE_OFFSETS = listOf(0.19f, 0.36f, 0.54f, 0.71f, 0.87f)

/** Группа полос, которая получает конкретный скачок. */
private enum class GlitchGroup { A, B }

/** Один мгновенный переход группы полос в новую позицию внутри минутного цикла. */
private data class GlitchEvent(
    val atMillis: Int,
    val group: GlitchGroup,
    val shiftFraction: Float,
)

/**
 * Compose-кнопка плеера с бесконечной глитч-анимацией.
 *
 * Расписание скачков генерируется при создании Composition, повторяется раз в
 * минуту и заменяется новым при следующем открытии экрана. Пока кнопка зажата,
 * кольцо и Play независимо масштабируются одной синхронной Transition.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerIconButton(
    modifier: Modifier = Modifier,
    showPreviewGlitch: Boolean = false,
    onClick: () -> Unit,
) {
    val glitchSequence = remember { generateGlitchSequence() }
    var groupAShift by remember { mutableFloatStateOf(0f) }
    var groupBShift by remember { mutableFloatStateOf(0f) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressTransition = updateTransition(
        targetState = isPressed,
        label = "playerButtonPress",
    )
    val ringScale by pressTransition.animateFloat(
        transitionSpec = {
            spring(
                dampingRatio = 0.72f,
                stiffness = 380f,
            )
        },
        label = "ringScale",
    ) { pressed ->
        if (pressed) 1.3f else 1f
    }
    val playScale by pressTransition.animateFloat(
        transitionSpec = {
            spring(
                dampingRatio = 0.72f,
                stiffness = 420f,
            )
        },
        label = "playScale",
    ) { pressed ->
        if (pressed) 0.7f else 1f
    }

    LaunchedEffect(glitchSequence) {
        while (true) {
            var elapsedMillis = 0
            glitchSequence.forEach { event ->
                delay((event.atMillis - elapsedMillis).toLong())
                when (event.group) {
                    GlitchGroup.A -> groupAShift = event.shiftFraction
                    GlitchGroup.B -> groupBShift = event.shiftFraction
                }
                elapsedMillis = event.atMillis
            }
            delay((GLITCH_CYCLE_DURATION_MILLIS - elapsedMillis).toLong())
            groupAShift = 0f
            groupBShift = 0f
        }
    }

    BoxWithConstraints(
        modifier = modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            role = Role.Button,
            onLongClick = {
                // Наличие обработчика отделяет long-press от обычного onClick.
            },
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        val iconSize = minOf(maxWidth, maxHeight) * 0.8f
        val iconTopOffset = (maxHeight - iconSize) / 2
        val ringPainter = painterResource(R.drawable.ic_player_ring)
        val playPainter = painterResource(R.drawable.ic_player_play)
        val effectiveGroupAShift = when {
            isPressed -> 0f
            showPreviewGlitch -> 0.06f
            else -> groupAShift
        }
        val effectiveGroupBShift = when {
            isPressed -> 0f
            showPreviewGlitch -> -0.06f
            else -> groupBShift
        }
        val groupAShiftDp = iconSize * effectiveGroupAShift
        val groupBShiftDp = iconSize * effectiveGroupBShift

        Image(
            painter = ringPainter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    scaleX = ringScale
                    scaleY = ringScale
                },
        )
        Image(
            painter = playPainter,
            contentDescription = stringResource(R.string.player_button_content_description),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    scaleX = playScale
                    scaleY = playScale
                },
        )
        GROUP_A_STRIPE_OFFSETS.forEach { topOffsetFraction ->
            PlayerGlitchStripe(
                ringPainter = ringPainter,
                playPainter = playPainter,
                imageSize = iconSize,
                imageTopOffset = iconTopOffset,
                topOffset = iconTopOffset + iconSize * topOffsetFraction,
                stripeHeight = 3.dp,
                horizontalShift = groupAShiftDp,
            )
        }
        GROUP_B_STRIPE_OFFSETS.forEach { topOffsetFraction ->
            PlayerGlitchStripe(
                ringPainter = ringPainter,
                playPainter = playPainter,
                imageSize = iconSize,
                imageTopOffset = iconTopOffset,
                topOffset = iconTopOffset + iconSize * topOffsetFraction,
                stripeHeight = 2.dp,
                horizontalShift = groupBShiftDp,
            )
        }
    }
}

/** Создаёт случайные серии резких скачков, похожие на исходную пульсацию. */
private fun generateGlitchSequence(random: Random = Random.Default): List<GlitchEvent> {
    val events = mutableListOf<GlitchEvent>()
    val latestEventMillis = GLITCH_CYCLE_DURATION_MILLIS - GLITCH_SEQUENCE_END_MARGIN_MILLIS
    var cursorMillis = random.nextInt(from = 1_200, until = 3_500)
    var group = if (random.nextBoolean()) GlitchGroup.A else GlitchGroup.B

    while (cursorMillis < latestEventMillis) {
        val stepCount = random.nextInt(from = 4, until = 7)
        var direction = if (random.nextBoolean()) 1f else -1f

        repeat(stepCount) {
            if (cursorMillis < latestEventMillis) {
                val amplitude = 0.025f + random.nextFloat() * 0.05f
                events += GlitchEvent(
                    atMillis = cursorMillis,
                    group = group,
                    shiftFraction = amplitude * direction,
                )
                cursorMillis += random.nextInt(from = 35, until = 75)
                direction *= -1f
            }
        }

        events += GlitchEvent(
            atMillis = cursorMillis.coerceAtMost(latestEventMillis),
            group = group,
            shiftFraction = 0f,
        )
        cursorMillis += random.nextInt(from = 2_500, until = 6_000)
        group = if (group == GlitchGroup.A) GlitchGroup.B else GlitchGroup.A
    }

    return events
}

/** Рисует одну обрезанную глитч-полосу поверх основной иконки. */
@Composable
private fun BoxScope.PlayerGlitchStripe(
    ringPainter: Painter,
    playPainter: Painter,
    imageSize: Dp,
    imageTopOffset: Dp,
    topOffset: Dp,
    stripeHeight: Dp,
    horizontalShift: Dp,
) {
    if (horizontalShift == 0.dp) return

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(stripeHeight)
            .align(Alignment.TopCenter)
            .offset(y = topOffset)
            .clipToBounds(),
    ) {
        val layerModifier = Modifier
            .align(Alignment.TopCenter)
            .wrapContentSize(
                align = Alignment.TopCenter,
                unbounded = true,
            )
            .offset(x = horizontalShift, y = imageTopOffset - topOffset)
            .requiredSize(imageSize)

        Image(
            painter = ringPainter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = layerModifier,
        )
        Image(
            painter = playPainter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = layerModifier,
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
