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
 * [RadialMenuAnimationStyle.Expand]. Меню поддерживает нажатие с протягиванием
 * пальца и закрывается после выбора. Радиусы задаются долей от меньшей стороны.
 */
@Composable
fun RadialMenu(
    items: List<RadialMenuItem>,
    visible: Boolean,
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
    val glitchFrames = remember { generateRadialMenuGlitchFrames() }
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
    val isInputReady = when (animationStyle) {
        RadialMenuAnimationStyle.GlitchFlicker -> glitchFrame.isStable
        RadialMenuAnimationStyle.Expand -> expansionProgress >= RADIAL_MENU_INPUT_PROGRESS
    }
    var pressedIndex by remember { mutableIntStateOf(-1) }
    val currentInputReady = rememberUpdatedState(isInputReady)
    val currentOnItemClick = rememberUpdatedState(onItemClick)
    val currentOnDismiss = rememberUpdatedState(onDismiss)
    val density = LocalDensity.current
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

    val inputModifier = if (visible) {
        Modifier.pointerInput(
            items,
            startAngle,
            safeTotalSweep,
            safeGap,
            safeInnerRadiusFraction,
            safeOuterRadiusFraction,
        ) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                if (!currentInputReady.value) {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change -> change.consume() }
                        if (event.changes.none { change -> change.pressed }) break
                    }
                    return@awaitEachGesture
                }

                var selectedIndex = findRadialMenuItemAt(
                    position = down.position,
                    width = size.width.toFloat(),
                    height = size.height.toFloat(),
                    itemCount = items.size,
                    startAngle = startAngle,
                    totalSweepAngle = safeTotalSweep,
                    gapAngle = safeGap,
                    innerRadiusFraction = safeInnerRadiusFraction,
                    outerRadiusFraction = safeOuterRadiusFraction,
                )
                pressedIndex = selectedIndex
                var releasedPosition: Offset? = null

                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        releasedPosition = change.position
                        change.consume()
                        break
                    }

                    selectedIndex = findRadialMenuItemAt(
                        position = change.position,
                        width = size.width.toFloat(),
                        height = size.height.toFloat(),
                        itemCount = items.size,
                        startAngle = startAngle,
                        totalSweepAngle = safeTotalSweep,
                        gapAngle = safeGap,
                        innerRadiusFraction = safeInnerRadiusFraction,
                        outerRadiusFraction = safeOuterRadiusFraction,
                    )
                    pressedIndex = selectedIndex
                    change.consume()
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

                pressedIndex = -1
                if (selectedIndex >= 0 && releasedIndex == selectedIndex) {
                    currentOnItemClick.value(items[selectedIndex])
                } else {
                    currentOnDismiss.value()
                }
            }
        }
    } else {
        Modifier
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
            val currentStart = slotMiddle - currentSweep / 2f + itemAnimation.angleOffset
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

            drawPath(
                path = segmentPath,
                brush = Brush.linearGradient(
                    colors = listOf(
                        segmentColor.darken(0.72f).copy(alpha = 0.84f * itemAlpha),
                        segmentColor.copy(alpha = 0.47f * itemAlpha),
                    ),
                    start = center,
                    end = gradientEnd,
                ),
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

/** Проигрывает последовательность резких, неинтерполированных мерцаний. */
@Composable
private fun rememberRadialMenuGlitchFrame(
    visible: Boolean,
    frames: List<RadialMenuGlitchFrame>,
): RadialMenuGlitchFrame {
    var frameIndex by remember { mutableIntStateOf(-1) }

    LaunchedEffect(visible, frames) {
        if (!visible) {
            frameIndex = -1
            return@LaunchedEffect
        }

        frames.forEachIndexed { index, frame ->
            frameIndex = index
            if (frame.holdMillis > 0L) {
                delay(frame.holdMillis)
            }
        }
    }

    return if (visible && frameIndex >= 0) {
        frames[frameIndex]
    } else {
        HIDDEN_RADIAL_MENU_GLITCH_FRAME
    }
}

/** Геометрия и прозрачность одного сектора в конкретном кадре появления. */
private data class RadialMenuItemAnimation(
    val outerRadius: Float,
    val sweepAngle: Float,
    val angleOffset: Float,
    val alpha: Float,
    val contentAlpha: Float,
)

/** Один дискретный кадр глитч-мерцания всего меню. */
private data class RadialMenuGlitchFrame(
    val visibleItemsMask: Int,
    val opacity: Float,
    val holdMillis: Long,
    val radiusScale: Float = 1f,
    val angleOffset: Float = 0f,
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
    val offsetDirection = if (index % 2 == 0) 1f else -1f

    return RadialMenuItemAnimation(
        outerRadius = finalOuterRadius * frame.radiusScale,
        sweepAngle = finalVisibleSweep,
        angleOffset = frame.angleOffset * offsetDirection,
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
        angleOffset = 0f,
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

/**
 * Создаёт новое расписание мерцаний с общей длительностью ровно
 * [RADIAL_MENU_GLITCH_DURATION_MILLIS]. Минимумы сохраняют читаемый ритм,
 * оставшееся время случайно распределяется порциями по 5 мс.
 */
private fun generateRadialMenuGlitchFrames(
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

    return listOf(
        RadialMenuGlitchFrame(
            visibleItemsMask = 0b010101,
            opacity = 0.55f,
            holdMillis = holdMillis[0],
            radiusScale = 0.992f,
            angleOffset = 1.4f,
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
            radiusScale = 1.008f,
            angleOffset = -1.1f,
        ),
        RadialMenuGlitchFrame(
            visibleItemsMask = 0b001101,
            opacity = 0.36f,
            holdMillis = holdMillis[3],
            radiusScale = 0.986f,
            angleOffset = 1.8f,
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
            radiusScale = 1.004f,
            angleOffset = -0.7f,
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
private const val RADIAL_MENU_GLITCH_RANDOM_QUANTUM_MILLIS = 5L
private val RADIAL_MENU_GLITCH_MIN_HOLDS_MILLIS = listOf(45L, 20L, 50L, 25L, 60L, 30L)
private const val RADIAL_MENU_ANIMATION_DURATION_MILLIS = 520
private const val RADIAL_MENU_STAGGER_FRACTION = 0.055f
private const val RADIAL_MENU_INPUT_PROGRESS = 0.85f
private val RADIAL_MENU_EASING = CubicBezierEasing(0.18f, 0.8f, 0.2f, 1f)
