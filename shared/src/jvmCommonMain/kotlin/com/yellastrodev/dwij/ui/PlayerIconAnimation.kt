package com.yellastrodev.dwij.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yellastrodev.dwij.resources.*
import com.yellastrodev.dwij.ui.theme.DwijColors
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val GLITCH_CYCLE_DURATION_MILLIS = 60_000
private const val GLITCH_SEQUENCE_END_MARGIN_MILLIS = 1_000
private const val PLAYER_ARTWORK_VIEWPORT_WIDTH = 355f
private const val PLAYER_ARTWORK_VIEWPORT_HEIGHT = 237f
private const val PLAYER_PROGRESS_CENTER_X = 180f
private const val PLAYER_PROGRESS_CENTER_Y = 120f
private const val PLAYER_PROGRESS_RADIUS_X = 81f
private const val PLAYER_PROGRESS_RADIUS_Y = 88f
private const val PLAYER_PROGRESS_HEAD_SIZE_DP = 35f
private const val PLAYER_ACCENT_VISIBLE_RADIUS_X = 85.25f
private const val PLAYER_ACCENT_VISIBLE_RADIUS_Y = 92.25f
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
 * при раскрытии меню акцентное кольцо дорастает до переданного внешнего радиуса,
 * а Play уменьшается уже при первоначальном нажатии. Внешний обработчик может
 * управлять pressed-состоянием, отключив встроенные жесты. Фоновый слой
 * использует общий с остальными холст, но остаётся неподвижным. Состояние
 * воспроизведения заменяет Play на Pause, а [progress] открывает отдельный
 * белый слой кольца от верхней точки по часовой стрелке.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerIconButton(
    modifier: Modifier = Modifier,
    showPreviewGlitch: Boolean = false,
    isPlaying: Boolean = false,
    progress: Float = 0f,
    expanded: Boolean = false,
    pressed: Boolean = false,
    gesturesEnabled: Boolean = true,
    expandedAccentOuterRadiusFraction: Float = 0.49f,
    onLongClick: () -> Unit = {},
    onPressedChange: (Boolean) -> Unit = {},
    onClick: () -> Unit,
) {
    val glitchSequence = remember { generateGlitchSequence() }
    var groupAShift by remember { mutableFloatStateOf(0f) }
    var groupBShift by remember { mutableFloatStateOf(0f) }
    val interactionSource = remember { MutableInteractionSource() }
    val isInternallyPressed by interactionSource.collectIsPressedAsState()
    val isPressed = if (gesturesEnabled) isInternallyPressed else pressed
    val currentOnPressedChange by rememberUpdatedState(onPressedChange)
    val shouldExpand = isPressed || expanded
    val safeExpandedRadiusFraction = expandedAccentOuterRadiusFraction.coerceIn(0f, 0.5f)
    val expandedRingScaleX =
        safeExpandedRadiusFraction * PLAYER_ARTWORK_VIEWPORT_WIDTH / PLAYER_ACCENT_VISIBLE_RADIUS_X
    val expandedRingScaleY =
        safeExpandedRadiusFraction * PLAYER_ARTWORK_VIEWPORT_WIDTH / PLAYER_ACCENT_VISIBLE_RADIUS_Y
    val ringTransition = updateTransition(
        targetState = expanded,
        label = "playerMenuExpansion",
    )
    val pressTransition = updateTransition(
        targetState = shouldExpand,
        label = "playerButtonPress",
    )
    val ringScaleX by ringTransition.animateFloat(
        transitionSpec = {
            spring(
                dampingRatio = 0.72f,
                stiffness = 380f,
            )
        },
        label = "ringScaleX",
    ) { pressed ->
        if (pressed) expandedRingScaleX else 1f
    }
    val ringScaleY by ringTransition.animateFloat(
        transitionSpec = {
            spring(
                dampingRatio = 0.72f,
                stiffness = 380f,
            )
        },
        label = "ringScaleY",
    ) { pressed ->
        if (pressed) expandedRingScaleY else 1f
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

    LaunchedEffect(isPressed) {
        currentOnPressedChange(isPressed)
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

    val gestureModifier = if (gesturesEnabled) {
        Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            role = Role.Button,
            onLongClick = onLongClick,
            onClick = onClick,
        )
    } else {
        Modifier
    }

    BoxWithConstraints(
        modifier = modifier.then(gestureModifier),
        contentAlignment = Alignment.Center,
    ) {
        val artworkSize = minOf(maxWidth, maxHeight)
        val artworkTopOffset = (maxHeight - artworkSize) / 2
        val artworkContentHeight = artworkSize * (237f / 355f)
        val artworkContentTopOffset = (maxHeight - artworkContentHeight) / 2
        val backgroundPainter = painterResource(Res.drawable.bg_player_glitch_v2)
        val ringPainter = painterResource(Res.drawable.ic_player_progress_ring_base)
        val progressPainter = painterResource(Res.drawable.ic_player_progress_ring_fill)
        val playPainter = painterResource(
            if (isPlaying) {
                Res.drawable.ic_player_pause_v2
            } else {
                Res.drawable.ic_player_play_v2
            },
        )
        val effectiveGroupAShift = when {
            shouldExpand -> 0f
            showPreviewGlitch -> 0.06f
            else -> groupAShift
        }
        val effectiveGroupBShift = when {
            shouldExpand -> 0f
            showPreviewGlitch -> -0.06f
            else -> groupBShift
        }
        val groupAShiftDp = artworkSize * effectiveGroupAShift
        val groupBShiftDp = artworkSize * effectiveGroupBShift

        Image(
            painter = backgroundPainter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(artworkSize),
        )

        Image(
            painter = ringPainter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(artworkSize)
                .graphicsLayer {
                    scaleX = ringScaleX
                    scaleY = ringScaleY
                },
        )
        PlayerProgressRing(
            painter = progressPainter,
            progress = progress,
            artworkSize = artworkSize,
            scaleX = ringScaleX,
            scaleY = ringScaleY,
        )
        Image(
            painter = playPainter,
            contentDescription = stringResource(
                if (isPlaying) {
                    Res.string.home_player_pause_content_description
                } else {
                    Res.string.home_player_play_content_description
                },
            ),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(artworkSize)
                .graphicsLayer {
                    scaleX = playScale
                    scaleY = playScale
                },
        )
        GROUP_A_STRIPE_OFFSETS.forEach { topOffsetFraction ->
            PlayerGlitchStripe(
                ringPainter = ringPainter,
                playPainter = playPainter,
                imageSize = artworkSize,
                imageTopOffset = artworkTopOffset,
                topOffset = artworkContentTopOffset + artworkContentHeight * topOffsetFraction,
                stripeHeight = 3.dp,
                horizontalShift = groupAShiftDp,
            )
        }
        GROUP_B_STRIPE_OFFSETS.forEach { topOffsetFraction ->
            PlayerGlitchStripe(
                ringPainter = ringPainter,
                playPainter = playPainter,
                imageSize = artworkSize,
                imageTopOffset = artworkTopOffset,
                topOffset = artworkContentTopOffset + artworkContentHeight * topOffsetFraction,
                stripeHeight = 2.dp,
                horizontalShift = groupBShiftDp,
            )
        }
    }
}

/** Показывает отделённый белый эллипс только в угловом секторе прогресса. */
@Composable
private fun BoxScope.PlayerProgressRing(
    painter: Painter,
    progress: Float,
    artworkSize: Dp,
    scaleX: Float,
    scaleY: Float,
) {
    val safeProgress = progress.coerceIn(0f, 1f)
    if (safeProgress <= 0f) return

    Image(
        painter = painter,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .size(artworkSize)
            .graphicsLayer {
                this.scaleX = scaleX
                this.scaleY = scaleY
            }
            .drawWithProgressSector(safeProgress),
    )
    PlayerProgressHead(
        progress = safeProgress,
        artworkSize = artworkSize,
        scaleX = scaleX,
        scaleY = scaleY,
    )
}

/** Ставит отдельный SVG-маркер на вычисленную точку эллипса и разворачивает его по касательной. */
@Composable
private fun BoxScope.PlayerProgressHead(
    progress: Float,
    artworkSize: Dp,
    scaleX: Float,
    scaleY: Float,
) {
    val headPainter = painterResource(Res.drawable.ic_player_progress_head)
    val artworkHeight = artworkSize *
        (PLAYER_ARTWORK_VIEWPORT_HEIGHT / PLAYER_ARTWORK_VIEWPORT_WIDTH)
    val centerX = artworkSize *
        (PLAYER_PROGRESS_CENTER_X / PLAYER_ARTWORK_VIEWPORT_WIDTH)
    val centerY = (artworkSize - artworkHeight) / 2f + artworkSize *
        (PLAYER_PROGRESS_CENTER_Y / PLAYER_ARTWORK_VIEWPORT_WIDTH)
    val radiusX = artworkSize *
        (PLAYER_PROGRESS_RADIUS_X / PLAYER_ARTWORK_VIEWPORT_WIDTH)
    val radiusY = artworkSize *
        (PLAYER_PROGRESS_RADIUS_Y / PLAYER_ARTWORK_VIEWPORT_WIDTH)
    val angleRadians = Math.toRadians((-90f + progress * 360f).toDouble())
    val cosAngle = cos(angleRadians).toFloat()
    val sinAngle = sin(angleRadians).toFloat()
    val headCenterX = centerX + radiusX * cosAngle
    val headCenterY = centerY + radiusY * sinAngle
    val tangentAngleDegrees = Math.toDegrees(
        atan2(
            y = radiusY.value * cosAngle,
            x = -radiusX.value * sinAngle,
        ).toDouble(),
    ).toFloat()
    val headSize = PLAYER_PROGRESS_HEAD_SIZE_DP.dp

    Box(
        modifier = Modifier
            .size(artworkSize)
            .graphicsLayer {
                this.scaleX = scaleX
                this.scaleY = scaleY
            },
    ) {
        Image(
            painter = headPainter,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .offset(
                    x = headCenterX - headSize / 2f,
                    y = headCenterY - headSize / 2f,
                )
                .size(headSize)
                .graphicsLayer {
                    rotationZ = tangentAngleDegrees
                },
        )
    }
}

/** Обрезает содержимое сектором от верхней точки по часовой стрелке. */
private fun Modifier.drawWithProgressSector(progress: Float): Modifier =
    drawWithContent {
        if (progress >= 1f) {
            drawContent()
            return@drawWithContent
        }

        val artworkScale = size.width / PLAYER_ARTWORK_VIEWPORT_WIDTH
        val artworkHeight = PLAYER_ARTWORK_VIEWPORT_HEIGHT * artworkScale
        val center = Offset(
            x = PLAYER_PROGRESS_CENTER_X * artworkScale,
            y = (size.height - artworkHeight) / 2f +
                PLAYER_PROGRESS_CENTER_Y * artworkScale,
        )
        val clipRadius = maxOf(size.width, size.height)
        val sector = Path().apply {
            moveTo(center.x, center.y)
            arcTo(
                rect = Rect(
                    left = center.x - clipRadius,
                    top = center.y - clipRadius,
                    right = center.x + clipRadius,
                    bottom = center.y + clipRadius,
                ),
                startAngleDegrees = -90f,
                sweepAngleDegrees = progress * 360f,
                forceMoveTo = false,
            )
            close()
        }
        clipPath(sector) {
            this@drawWithContent.drawContent()
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
@Preview(name = "Animated — Interactive Mode")
@Composable
private fun PlayerIconButtonPreview() {
    Box(
        modifier = Modifier
            .size(512.dp)
            .background(DwijColors.Background),
    ) {
        PlayerIconButton(modifier = Modifier.fillMaxSize(), onClick = {})
    }
}

/** Показывает заметный глитч-кадр в обычном статическом Preview. */
@Preview(name = "Static glitch frame")
@Composable
private fun PlayerIconButtonGlitchFramePreview() {
    Box(
        modifier = Modifier
            .size(512.dp)
            .background(DwijColors.Background),
    ) {
        PlayerIconButton(
            modifier = Modifier.fillMaxSize(),
            showPreviewGlitch = true,
            onClick = {},
        )
    }
}
