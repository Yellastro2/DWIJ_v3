package com.yellastrodev.dwij

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Один сектор кругового меню домашнего экрана. */
data class RadialMenuItem(
    val id: String,
    val title: String,
    val color: Color,
)

/** Доступные способы появления и исчезновения секторов кругового меню. */
enum class RadialMenuAnimationStyle {
    GlitchFlicker,
    Expand,
}

/**
 * Полностью Compose-версия кругового меню. По умолчанию сектора появляются
 * резкими глитч-мерцаниями; прежнее плавное выдвижение сохранено в режиме
 * [RadialMenuAnimationStyle.Expand]. Единый обработчик отличает быстрый клик
 * в центре от long-press, меняет выбранный сектор при протягивании и оставляет
 * центральную область без выбора. Радиусы задаются долей от меньшей стороны.
 */
@Composable
fun RadialMenu(
    items: List<RadialMenuItem>,
    visible: Boolean,
    onPrimaryClick: () -> Unit,
    onVisualActivation: () -> Unit,
    onPressChange: (Boolean) -> Unit,
    onItemClick: (RadialMenuItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    startAngle: Float = -90f,
    totalSweepAngle: Float = 360f,
    gapAngle: Float = 0f,
    innerRadiusFraction: Float = 0.11f,
    outerRadiusFraction: Float = 0.49f,
    animationStyle: RadialMenuAnimationStyle = RadialMenuAnimationStyle.GlitchFlicker,
) {
    val glitchFrames = remember { createFixedRadialMenuGlitchFrames() }
    val expansionProgress = rememberRadialMenuExpansionProgress(
        visible = visible && animationStyle == RadialMenuAnimationStyle.Expand,
    )
    val glitchFrame = rememberRadialMenuGlitchFrame(
        visible = visible && animationStyle == RadialMenuAnimationStyle.GlitchFlicker,
        frames = glitchFrames,
    )
    val isAnimationVisible = when (animationStyle) {
        RadialMenuAnimationStyle.GlitchFlicker -> glitchFrame.opacity > 0f
        RadialMenuAnimationStyle.Expand -> expansionProgress > 0f
    }
    var pressedIndex by remember { mutableIntStateOf(-1) }
    val currentOnPrimaryClick = rememberUpdatedState(onPrimaryClick)
    val currentOnVisualActivation = rememberUpdatedState(onVisualActivation)
    val currentOnPressChange = rememberUpdatedState(onPressChange)
    val currentOnItemClick = rememberUpdatedState(onItemClick)
    val currentOnDismiss = rememberUpdatedState(onDismiss)
    val density = LocalDensity.current
    val longPressTimeoutMillis = LocalViewConfiguration.current.longPressTimeoutMillis
    val contourWidth = with(density) { 1.4.dp.toPx() }
    val glowWidth = with(density) { 4.5.dp.toPx() }
    val glitchWidth = with(density) { 1.1.dp.toPx() }
    val textSize = with(density) { 13.sp.toPx() }
    val textPaint = remember(textSize) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            this.textSize = textSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }
    val safeTotalSweep = totalSweepAngle.coerceIn(1f, 360f)
    val safeGap = gapAngle.coerceAtLeast(0f)
    val safeInnerRadiusFraction = innerRadiusFraction.coerceIn(0f, 0.48f)
    val safeOuterRadiusFraction = outerRadiusFraction.coerceIn(
        safeInnerRadiusFraction + 0.01f,
        0.5f,
    )

    val inputModifier = Modifier.pointerInput(
        items,
        startAngle,
        safeTotalSweep,
        safeGap,
        safeInnerRadiusFraction,
        safeOuterRadiusFraction,
        longPressTimeoutMillis,
    ) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val startedInsideCenter = isInsideRadialCenter(
                position = down.position,
                width = size.width.toFloat(),
                height = size.height.toFloat(),
                radiusFraction = safeInnerRadiusFraction,
            )
            if (!startedInsideCenter) {
                return@awaitEachGesture
            }

            down.consume()
            currentOnPressChange.value(true)
            currentOnVisualActivation.value()
            pressedIndex = -1
            var latestPosition = down.position
            var stayedInsideCenter = true

            try {
                val releasedBeforeLongPress = withTimeoutOrNull(longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: return@withTimeoutOrNull false
                        latestPosition = change.position
                        val isStillInsideCenter = isInsideRadialCenter(
                            position = latestPosition,
                            width = size.width.toFloat(),
                            height = size.height.toFloat(),
                            radiusFraction = safeInnerRadiusFraction,
                        )
                        if (!isStillInsideCenter) {
                            stayedInsideCenter = false
                        }
                        change.consume()
                        if (!change.pressed) return@withTimeoutOrNull true
                    }
                }

                when (releasedBeforeLongPress) {
                    true -> {
                        if (stayedInsideCenter) currentOnPrimaryClick.value()
                        currentOnDismiss.value()
                        return@awaitEachGesture
                    }
                    false -> {
                        currentOnDismiss.value()
                        return@awaitEachGesture
                    }
                    null -> Unit
                }

                pressedIndex = findRadialMenuItemAt(
                    position = latestPosition,
                    width = size.width.toFloat(),
                    height = size.height.toFloat(),
                    itemCount = items.size,
                    startAngle = startAngle,
                    totalSweepAngle = safeTotalSweep,
                    gapAngle = safeGap,
                    innerRadiusFraction = safeInnerRadiusFraction,
                    outerRadiusFraction = safeOuterRadiusFraction,
                )

                var releasedPosition: Offset? = null
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    latestPosition = change.position
                    pressedIndex = findRadialMenuItemAt(
                        position = latestPosition,
                        width = size.width.toFloat(),
                        height = size.height.toFloat(),
                        itemCount = items.size,
                        startAngle = startAngle,
                        totalSweepAngle = safeTotalSweep,
                        gapAngle = safeGap,
                        innerRadiusFraction = safeInnerRadiusFraction,
                        outerRadiusFraction = safeOuterRadiusFraction,
                    )
                    change.consume()
                    if (!change.pressed) {
                        releasedPosition = latestPosition
                        break
                    }
                }

                val releasedIndex = releasedPosition?.let { position ->
                    findRadialMenuItemAt(
                        position = position,
                        width = size.width.toFloat(),
                        height = size.height.toFloat(),
                        itemCount = items.size,
                        startAngle = startAngle,
                        totalSweepAngle = safeTotalSweep,
                        gapAngle = safeGap,
                        innerRadiusFraction = safeInnerRadiusFraction,
                        outerRadiusFraction = safeOuterRadiusFraction,
                    )
                } ?: -1

                if (releasedIndex >= 0) {
                    currentOnItemClick.value(items[releasedIndex])
                } else {
                    currentOnDismiss.value()
                }
            } finally {
                pressedIndex = -1
                currentOnPressChange.value(false)
            }
        }
    }

    Canvas(modifier = modifier.then(inputModifier)) {
        if (items.isEmpty() || !isAnimationVisible) return@Canvas

        val center = Offset(size.width / 2f, size.height / 2f)
        val minDimension = min(size.width, size.height)
        val finalInnerRadius = minDimension * safeInnerRadiusFraction
        val finalOuterRadius = minDimension * safeOuterRadiusFraction
        val itemSlotAngle = safeTotalSweep / items.size
        val finalVisibleSweep = (itemSlotAngle - safeGap).coerceAtLeast(0.5f)

        items.forEachIndexed { index, item ->
            val itemAnimation = when (animationStyle) {
                RadialMenuAnimationStyle.GlitchFlicker -> radialMenuGlitchItemAnimation(
                    frame = glitchFrame,
                    index = index,
                    finalOuterRadius = finalOuterRadius,
                    finalVisibleSweep = finalVisibleSweep,
                )
                RadialMenuAnimationStyle.Expand -> radialMenuExpandItemAnimation(
                    animationProgress = expansionProgress,
                    index = index,
                    itemCount = items.size,
                    finalInnerRadius = finalInnerRadius,
                    finalOuterRadius = finalOuterRadius,
                    finalVisibleSweep = finalVisibleSweep,
                )
            } ?: return@forEachIndexed

            val outerRadius = itemAnimation.outerRadius
            val currentSweep = itemAnimation.sweepAngle
            val slotMiddle = startAngle + itemSlotAngle * (index + 0.5f)
            val currentStart = slotMiddle - currentSweep / 2f
            val itemAlpha = itemAnimation.alpha
            val segmentColor = if (index == pressedIndex) {
                item.color.lighten(0.24f)
            } else {
                item.color
            }
            val segmentPath = buildRadialSegmentPath(
                center = center,
                innerRadius = finalInnerRadius,
                outerRadius = outerRadius,
                startAngle = currentStart,
                sweepAngle = currentSweep,
            )
            val gradientEnd = center + Offset(
                x = outerRadius * cosDegrees(currentStart + currentSweep / 2f),
                y = outerRadius * sinDegrees(currentStart + currentSweep / 2f),
            )
            val segmentBrush = Brush.linearGradient(
                colors = listOf(
                    segmentColor.darken(0.72f).copy(alpha = 0.84f * itemAlpha),
                    segmentColor.copy(alpha = 0.47f * itemAlpha),
                ),
                start = center,
                end = gradientEnd,
            )

            drawPath(
                path = segmentPath,
                brush = segmentBrush,
            )
            drawPath(
                path = segmentPath,
                color = segmentColor.copy(alpha = 0.18f * itemAlpha),
                style = Stroke(width = glowWidth),
            )
            drawPath(
                path = segmentPath,
                color = segmentColor.copy(alpha = 0.92f * itemAlpha),
                style = Stroke(width = contourWidth),
            )
            if (index == pressedIndex) {
                drawPath(
                    path = segmentPath,
                    brush = segmentBrush,
                )
                drawPath(
                    path = segmentPath,
                    color = Color.White.copy(alpha = 0.34f * itemAlpha),
                    style = Stroke(width = contourWidth * 1.6f),
                )
            }

            drawRadialMenuGlitches(
                index = index,
                color = segmentColor,
                center = center,
                innerRadius = finalInnerRadius,
                outerRadius = outerRadius,
                startAngle = currentStart,
                sweepAngle = currentSweep,
                strokeWidth = glitchWidth,
                alphaMultiplier = itemAlpha,
            )

            val contentProgress = itemAnimation.contentAlpha
            if (contentProgress > 0f) {
                val contentRadius = finalInnerRadius +
                    (outerRadius - finalInnerRadius) * 0.55f
                val contentAngle = currentStart + currentSweep / 2f
                val textPosition = center + Offset(
                    x = contentRadius * cosDegrees(contentAngle),
                    y = contentRadius * sinDegrees(contentAngle),
                )
                drawIntoCanvas { canvas ->
                    textPaint.alpha = (255 * contentProgress).toInt()
                    val metrics = textPaint.fontMetrics
                    val baseline = textPosition.y - (metrics.ascent + metrics.descent) / 2f
                    canvas.nativeCanvas.drawText(
                        item.title,
                        textPosition.x,
                        baseline,
                        textPaint,
                    )
                    textPaint.alpha = 255
                }
            }
        }
    }
}

/** Сохраняет прежнее плавное выдвижение секторов как переключаемый режим. */
@Composable
private fun rememberRadialMenuExpansionProgress(visible: Boolean): Float {
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = RADIAL_MENU_ANIMATION_DURATION_MILLIS,
            easing = LinearEasing,
        ),
        label = "radialMenuExpansionProgress",
    )
    return progress
}

/** Проигрывает последовательность мерцаний вперёд при входе и назад при выходе. */
@Composable
private fun rememberRadialMenuGlitchFrame(
    visible: Boolean,
    frames: List<RadialMenuGlitchFrame>,
): RadialMenuGlitchFrame {
    var frameIndex by remember { mutableIntStateOf(-1) }

    LaunchedEffect(visible, frames) {
        if (visible) {
            frames.forEachIndexed { index, frame ->
                frameIndex = index
                if (frame.holdMillis > 0L) {
                    delay(frame.holdMillis)
                }
            }
        } else if (frameIndex >= 0) {
            for (index in frameIndex.coerceAtMost(frames.lastIndex) downTo 0) {
                frameIndex = index
                val holdMillis = frames[index].holdMillis
                if (holdMillis > 0L) {
                    delay(holdMillis)
                }
            }
            frameIndex = -1
        }
    }

    return if (frameIndex >= 0) {
        frames[frameIndex]
    } else {
        HIDDEN_RADIAL_MENU_GLITCH_FRAME
    }
}

/** Геометрия и прозрачность одного сектора в конкретном кадре появления. */
private data class RadialMenuItemAnimation(
    val outerRadius: Float,
    val sweepAngle: Float,
    val alpha: Float,
    val contentAlpha: Float,
)

/** Один дискретный кадр глитч-мерцания всего меню. */
private data class RadialMenuGlitchFrame(
    val visibleItemsMask: Int,
    val opacity: Float,
    val holdMillis: Long,
    val isStable: Boolean = false,
) {
    fun isItemVisible(index: Int): Boolean =
        visibleItemsMask == ALL_RADIAL_MENU_ITEMS_MASK ||
            (visibleItemsMask and (1 shl (index % RADIAL_MENU_ITEM_MASK_BITS))) != 0
}

/** Возвращает полный сектор для резкого глитч-кадра без выдвижения из центра. */
private fun radialMenuGlitchItemAnimation(
    frame: RadialMenuGlitchFrame,
    index: Int,
    finalOuterRadius: Float,
    finalVisibleSweep: Float,
): RadialMenuItemAnimation? {
    if (!frame.isItemVisible(index) || frame.opacity <= 0f) return null

    val itemOpacityFactor = if (frame.isStable) {
        1f
    } else {
        when (index % 3) {
            0 -> 0.82f
            1 -> 1f
            else -> 0.91f
        }
    }
    val alpha = (frame.opacity * itemOpacityFactor).coerceIn(0f, 1f)

    return RadialMenuItemAnimation(
        outerRadius = finalOuterRadius,
        sweepAngle = finalVisibleSweep,
        alpha = alpha,
        contentAlpha = alpha,
    )
}

/** Воспроизводит прежнюю геометрию плавного роста одного сектора. */
private fun radialMenuExpandItemAnimation(
    animationProgress: Float,
    index: Int,
    itemCount: Int,
    finalInnerRadius: Float,
    finalOuterRadius: Float,
    finalVisibleSweep: Float,
): RadialMenuItemAnimation? {
    val localProgress = radialMenuItemProgress(
        animationProgress = animationProgress,
        index = index,
        itemCount = itemCount,
    )
    if (localProgress <= 0f) return null

    val easedProgress = RADIAL_MENU_EASING.transform(localProgress)
    val outerRadius = lerp(
        from = finalInnerRadius,
        to = finalOuterRadius,
        progress = easedProgress,
    )
    val angularProgress = ((localProgress - 0.08f) / 0.92f)
        .coerceIn(0f, 1f)
    val currentSweep = lerp(
        from = min(8f, finalVisibleSweep),
        to = finalVisibleSweep,
        progress = RADIAL_MENU_EASING.transform(angularProgress),
    )
    val contentProgress = ((localProgress - 0.46f) / 0.54f)
        .coerceIn(0f, 1f)

    return RadialMenuItemAnimation(
        outerRadius = outerRadius,
        sweepAngle = currentSweep,
        alpha = 1f,
        contentAlpha = contentProgress,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRadialMenuGlitches(
    index: Int,
    color: Color,
    center: Offset,
    innerRadius: Float,
    outerRadius: Float,
    startAngle: Float,
    sweepAngle: Float,
    strokeWidth: Float,
    alphaMultiplier: Float,
) {
    val outerTopLeft = Offset(center.x - outerRadius, center.y - outerRadius)
    val outerSize = Size(outerRadius * 2f, outerRadius * 2f)
    val innerTopLeft = Offset(center.x - innerRadius, center.y - innerRadius)
    val innerSize = Size(innerRadius * 2f, innerRadius * 2f)
    val shift = (index % 3) * 0.07f
    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Square)

    drawArc(
        color = color.copy(alpha = 0.86f * alphaMultiplier),
        startAngle = startAngle + sweepAngle * (0.10f + shift),
        sweepAngle = sweepAngle * 0.16f,
        useCenter = false,
        topLeft = outerTopLeft,
        size = outerSize,
        style = stroke,
    )
    drawArc(
        color = color.copy(alpha = 0.86f * alphaMultiplier),
        startAngle = startAngle + sweepAngle * (0.66f - shift),
        sweepAngle = sweepAngle * 0.11f,
        useCenter = false,
        topLeft = outerTopLeft,
        size = outerSize,
        style = stroke,
    )
    drawArc(
        color = Color.White.copy(alpha = 0.31f * alphaMultiplier),
        startAngle = startAngle + sweepAngle * (0.34f + shift),
        sweepAngle = sweepAngle * 0.08f,
        useCenter = false,
        topLeft = innerTopLeft,
        size = innerSize,
        style = stroke,
    )
    drawRadialGlitchLine(
        center = center,
        radius = outerRadius,
        angle = startAngle,
        color = color,
        seed = index,
        strokeWidth = strokeWidth,
        alphaMultiplier = alphaMultiplier,
    )
    drawRadialGlitchLine(
        center = center,
        radius = outerRadius,
        angle = startAngle + sweepAngle,
        color = color,
        seed = index + 1,
        strokeWidth = strokeWidth,
        alphaMultiplier = alphaMultiplier,
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRadialGlitchLine(
    center: Offset,
    radius: Float,
    angle: Float,
    color: Color,
    seed: Int,
    strokeWidth: Float,
    alphaMultiplier: Float,
) {
    val inner = radius - (10f + (seed % 3) * 3f) * density
    val outer = radius + (2f + seed % 2) * density
    drawLine(
        color = color.copy(alpha = 0.73f * alphaMultiplier),
        start = center + Offset(
            x = inner * cosDegrees(angle),
            y = inner * sinDegrees(angle),
        ),
        end = center + Offset(
            x = outer * cosDegrees(angle),
            y = outer * sinDegrees(angle),
        ),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Square,
    )
}

private fun buildRadialSegmentPath(
    center: Offset,
    innerRadius: Float,
    outerRadius: Float,
    startAngle: Float,
    sweepAngle: Float,
): Path = Path().apply {
    arcTo(
        rect = Rect(
            left = center.x - outerRadius,
            top = center.y - outerRadius,
            right = center.x + outerRadius,
            bottom = center.y + outerRadius,
        ),
        startAngleDegrees = startAngle,
        sweepAngleDegrees = sweepAngle,
        forceMoveTo = false,
    )
    arcTo(
        rect = Rect(
            left = center.x - innerRadius,
            top = center.y - innerRadius,
            right = center.x + innerRadius,
            bottom = center.y + innerRadius,
        ),
        startAngleDegrees = startAngle + sweepAngle,
        sweepAngleDegrees = -sweepAngle,
        forceMoveTo = false,
    )
    close()
}

/** Проверяет центральную мёртвую зону, в которой сектор не выбирается. */
private fun isInsideRadialCenter(
    position: Offset,
    width: Float,
    height: Float,
    radiusFraction: Float,
): Boolean {
    val center = Offset(width / 2f, height / 2f)
    val delta = position - center
    val radius = min(width, height) * radiusFraction
    return delta.x * delta.x + delta.y * delta.y <= radius * radius
}

private fun findRadialMenuItemAt(
    position: Offset,
    width: Float,
    height: Float,
    itemCount: Int,
    startAngle: Float,
    totalSweepAngle: Float,
    gapAngle: Float,
    innerRadiusFraction: Float,
    outerRadiusFraction: Float,
): Int {
    if (itemCount == 0) return -1

    val center = Offset(width / 2f, height / 2f)
    val delta = position - center
    val distanceSquared = delta.x * delta.x + delta.y * delta.y
    val minDimension = min(width, height)
    val innerRadius = minDimension * innerRadiusFraction
    val outerRadius = minDimension * outerRadiusFraction

    if (distanceSquared < innerRadius * innerRadius ||
        distanceSquared > outerRadius * outerRadius
    ) {
        return -1
    }

    val touchAngle = normalizeAngle(
        Math.toDegrees(atan2(delta.y.toDouble(), delta.x.toDouble())).toFloat(),
    )
    val relativeAngle = normalizeAngle(touchAngle - normalizeAngle(startAngle))
    if (totalSweepAngle < 360f && relativeAngle > totalSweepAngle) return -1

    val slotAngle = totalSweepAngle / itemCount
    val index = (relativeAngle / slotAngle).toInt().coerceAtMost(itemCount - 1)
    val angleInsideSlot = relativeAngle - index * slotAngle
    return if (
        angleInsideSlot >= gapAngle / 2f &&
        angleInsideSlot <= slotAngle - gapAngle / 2f
    ) {
        index
    } else {
        -1
    }
}

private fun radialMenuItemProgress(
    animationProgress: Float,
    index: Int,
    itemCount: Int,
): Float {
    if (itemCount <= 1) return animationProgress
    val delay = index * RADIAL_MENU_STAGGER_FRACTION
    val available = (1f - RADIAL_MENU_STAGGER_FRACTION * (itemCount - 1))
        .coerceAtLeast(0.05f)
    return ((animationProgress - delay) / available).coerceIn(0f, 1f)
}

private fun Color.darken(factor: Float): Color = copy(
    red = red * factor,
    green = green * factor,
    blue = blue * factor,
)

private fun Color.lighten(amount: Float): Color = copy(
    red = red + (1f - red) * amount,
    green = green + (1f - green) * amount,
    blue = blue + (1f - blue) * amount,
)

private fun cosDegrees(angle: Float): Float =
    cos(Math.toRadians(angle.toDouble())).toFloat()

private fun sinDegrees(angle: Float): Float =
    sin(Math.toRadians(angle.toDouble())).toFloat()

private fun normalizeAngle(angle: Float): Float = ((angle % 360f) + 360f) % 360f

private fun lerp(from: Float, to: Float, progress: Float): Float =
    from + (to - from) * progress

private const val ALL_RADIAL_MENU_ITEMS_MASK = -1
private const val RADIAL_MENU_ITEM_MASK_BITS = 30
private val HIDDEN_RADIAL_MENU_GLITCH_FRAME = RadialMenuGlitchFrame(
    visibleItemsMask = 0,
    opacity = 0f,
    holdMillis = 0L,
)

/** Создаёт используемый сейчас вручную настроенный ритм глитч-мерцаний. */
private fun createFixedRadialMenuGlitchFrames(): List<RadialMenuGlitchFrame> =
    createRadialMenuGlitchFrames(RADIAL_MENU_GLITCH_FIXED_HOLDS_MILLIS)

/**
 * Сохранённый случайный вариант расписания. Минимумы удерживают читаемый ритм,
 * оставшиеся миллисекунды распределяются между бликами порциями по 5 мс.
 */
@Suppress("unused")
private fun generateRandomRadialMenuGlitchFrames(
    random: Random = Random.Default,
): List<RadialMenuGlitchFrame> {
    val extraHoldMillis = MutableList(RADIAL_MENU_GLITCH_MIN_HOLDS_MILLIS.size) { 0L }
    val minimumDuration = RADIAL_MENU_GLITCH_MIN_HOLDS_MILLIS.sum()
    var remainingDuration = RADIAL_MENU_GLITCH_DURATION_MILLIS - minimumDuration

    while (remainingDuration >= RADIAL_MENU_GLITCH_RANDOM_QUANTUM_MILLIS) {
        val frameIndex = random.nextInt(extraHoldMillis.size)
        extraHoldMillis[frameIndex] += RADIAL_MENU_GLITCH_RANDOM_QUANTUM_MILLIS
        remainingDuration -= RADIAL_MENU_GLITCH_RANDOM_QUANTUM_MILLIS
    }
    repeat(remainingDuration.toInt()) {
        extraHoldMillis[random.nextInt(extraHoldMillis.size)] += 1L
    }

    val holdMillis = RADIAL_MENU_GLITCH_MIN_HOLDS_MILLIS.mapIndexed { index, minimum ->
        minimum + extraHoldMillis[index]
    }

    return createRadialMenuGlitchFrames(holdMillis)
}

/** Собирает одинаковую геометрию бликов с переданным расписанием кадров. */
private fun createRadialMenuGlitchFrames(
    holdMillis: List<Long>,
): List<RadialMenuGlitchFrame> {
    require(holdMillis.size == RADIAL_MENU_GLITCH_TRANSIENT_FRAME_COUNT)
    require(holdMillis.sum() == RADIAL_MENU_GLITCH_DURATION_MILLIS)

    return listOf(
        RadialMenuGlitchFrame(
            visibleItemsMask = 0b010101,
            opacity = 0.55f,
            holdMillis = holdMillis[0],
        ),
        RadialMenuGlitchFrame(
            visibleItemsMask = 0,
            opacity = 0f,
            holdMillis = holdMillis[1],
        ),
        RadialMenuGlitchFrame(
            visibleItemsMask = 0b111011,
            opacity = 0.88f,
            holdMillis = holdMillis[2],
        ),
        RadialMenuGlitchFrame(
            visibleItemsMask = 0b001101,
            opacity = 0.36f,
            holdMillis = holdMillis[3],
        ),
        RadialMenuGlitchFrame(
            visibleItemsMask = ALL_RADIAL_MENU_ITEMS_MASK,
            opacity = 1f,
            holdMillis = holdMillis[4],
        ),
        RadialMenuGlitchFrame(
            visibleItemsMask = 0b110111,
            opacity = 0.58f,
            holdMillis = holdMillis[5],
        ),
        RadialMenuGlitchFrame(
            visibleItemsMask = ALL_RADIAL_MENU_ITEMS_MASK,
            opacity = 1f,
            holdMillis = 0L,
            isStable = true,
        ),
    )
}

private const val RADIAL_MENU_GLITCH_DURATION_MILLIS = 500L
private const val RADIAL_MENU_GLITCH_TRANSIENT_FRAME_COUNT = 6
private const val RADIAL_MENU_GLITCH_RANDOM_QUANTUM_MILLIS = 5L
private val RADIAL_MENU_GLITCH_FIXED_HOLDS_MILLIS = listOf(65L, 35L, 110L, 45L, 170L, 75L)
private val RADIAL_MENU_GLITCH_MIN_HOLDS_MILLIS = listOf(45L, 20L, 50L, 25L, 60L, 30L)
private const val RADIAL_MENU_ANIMATION_DURATION_MILLIS = 520
private const val RADIAL_MENU_STAGGER_FRACTION = 0.055f
private val RADIAL_MENU_EASING = CubicBezierEasing(0.18f, 0.8f, 0.2f, 1f)
