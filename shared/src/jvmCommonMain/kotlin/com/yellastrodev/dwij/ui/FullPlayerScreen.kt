package com.yellastrodev.dwij.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.yellastrodev.dwij.resources.*
import com.yellastrodev.dwij.ui.theme.DwijColors
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.sp
import com.yellastrodev.dwij.utils.PlayerEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.util.Locale
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.yellastrodev.dwij.playback.PlayerVolumeControl
import kotlinx.coroutines.flow.MutableStateFlow

/** Неизменяемый снимок данных, необходимых полноэкранному плееру. */
@Immutable
data class FullPlayerUiState(
    val trackId: String?,
    val queueTitle: String,
    val queuePosition: Int,
    val title: String,
    val artist: String,
    val album: String?,
    val sourceLabel: String?,
    val hasMultipleSources: Boolean,
    val hasUnresolvedMatchCandidate: Boolean,
    val cover: ImageBitmap?,
    val isPlaying: Boolean,
    val currentPositionMillis: Long,
    val durationMillis: Long,
    val isShuffle: Boolean,
    val isRepeatAll: Boolean,
    val showPlaybackModes: Boolean,
    val canLike: Boolean,
    val isLiked: Boolean,
    val playlistTitles: List<String>,
    val isWaveLoading: Boolean = false,
)

/**
 * Полноэкранный плеер в визуальном стиле Движа.
 * Позиция ползунка меняется локально во время жеста, а seek отправляется плееру только при отпускании.
 */
@Composable
fun FullPlayerScreen(
    state: FullPlayerUiState,
    playerEvents: Flow<PlayerEvent>,
    uiMessages: Flow<String>,
    onBackClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffleClick: () -> Unit,
    onRepeatClick: () -> Unit,
    onLikeClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onSourcesClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = DwijColors.Background
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(playerEvents) {
        playerEvents.collect { event ->
            val message = when (event) {
                is PlayerEvent.ShowError -> event.message
                is PlayerEvent.TrackListEnd -> event.message
            }
            snackbarHostState.showSnackbar(message)
        }
    }
    LaunchedEffect(uiMessages) {
        uiMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor),
    ) {
        Image(
            painter = painterResource(Res.drawable.bg_player_glitch_v2),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = 0.22f,
            modifier = Modifier.fillMaxSize(),
        )
        Column(modifier = Modifier.fillMaxSize()) {
            FullPlayerTopBar(
                queueTitle = state.queueTitle,
                queuePosition = state.queuePosition,
                showSourcesIndicator = state.hasMultipleSources ||
                    state.hasUnresolvedMatchCandidate,
                onSourcesClick = onSourcesClick,
                onBackClick = onBackClick,
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = 6.dp),
            ) {
                FullPlayerCover(
                    trackId = state.trackId,
                    cover = state.cover,
                    isWaveLoading = state.isWaveLoading,
                    canLike = state.canLike,
                    isLiked = state.isLiked,
                    onLikeClick = onLikeClick,
                    modifier = Modifier.weight(1f),
                )
                FullPlayerMetadata(
                    title = state.title,
                    artist = state.artist,
                    album = state.album,
                    sourceLabel = state.sourceLabel,
                )
                FullPlayerProgress(
                    trackId = state.trackId,
                    currentPositionMillis = state.currentPositionMillis,
                    durationMillis = state.durationMillis,
                    onSeek = onSeek,
                )
                if (state.canLike) {
                    PlayerPlaylistMemberships(
                        playlistTitles = state.playlistTitles,
                        onAddToPlaylistClick = onAddToPlaylistClick,
                    )
                }
            }
            FullPlayerControls(
                enabled = state.trackId != null && !state.isWaveLoading,
                isPlaying = state.isPlaying,
                isShuffle = state.isShuffle,
                isRepeatAll = state.isRepeatAll,
                showPlaybackModes = state.showPlaybackModes,
                onPlayPauseClick = onPlayPauseClick,
                onPreviousClick = onPreviousClick,
                onNextClick = onNextClick,
                onShuffleClick = onShuffleClick,
                onRepeatClick = onRepeatClick,
                modifier = Modifier
                    .background(DwijColors.Background.copy(alpha = 0.96f))
                    .navigationBarsPadding(),
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(12.dp),
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = DwijColors.PlayerSnackbarBackground,
                contentColor = DwijColors.White,
                actionColor = DwijColors.Pink,
            )
        }
    }
}

/** Рисует закреплённую строку с возвратом и названием текущей очереди. */
@Composable
private fun FullPlayerTopBar(
    queueTitle: String,
    queuePosition: Int,
    showSourcesIndicator: Boolean,
    onSourcesClick: () -> Unit,
    onBackClick: () -> Unit,
) {
    val backDescription = stringResource(Res.string.player_back_content_description)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(DwijColors.Background.copy(alpha = 0.92f))
            .statusBarsPadding()
            .height(58.dp)
            .padding(horizontal = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(46.dp)
                .clickable(role = Role.Button, onClick = onBackClick)
                .semantics {
                    contentDescription = backDescription
                },
        ) {
            Canvas(modifier = Modifier.size(25.dp)) {
                val stroke = 2.dp.toPx()
                drawLine(
                    color = DwijColors.Cyan,
                    start = Offset(size.width * 0.72f + 1.dp.toPx(), size.height * 0.18f),
                    end = Offset(size.width * 0.28f + 1.dp.toPx(), size.height * 0.5f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
                drawLine(
                    color = DwijColors.Pink,
                    start = Offset(size.width * 0.72f - 1.dp.toPx(), size.height * 0.82f),
                    end = Offset(size.width * 0.28f - 1.dp.toPx(), size.height * 0.5f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
                drawLine(
                    color = DwijColors.White,
                    start = Offset(size.width * 0.72f, size.height * 0.18f),
                    end = Offset(size.width * 0.28f, size.height * 0.5f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
                drawLine(
                    color = DwijColors.White,
                    start = Offset(size.width * 0.28f, size.height * 0.5f),
                    end = Offset(size.width * 0.72f, size.height * 0.82f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = stringResource(Res.string.player_now_playing, queuePosition),
                color = DwijColors.Pink,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                maxLines = 1,
            )
            Text(
                text = queueTitle,
                color = DwijColors.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showSourcesIndicator) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(46.dp)
                    .clickable(role = Role.Button, onClick = onSourcesClick),
            ) {
                MultipleSourcesIndicator(modifier = Modifier.size(34.dp))
            }
        } else {
            Spacer(modifier = Modifier.width(46.dp))
        }
    }
}

/** Показывает обложку и повторяет механику старого View: тап показывает сердце, double-tap меняет лайк. */
@Composable
private fun FullPlayerCover(
    trackId: String?,
    cover: ImageBitmap?,
    isWaveLoading: Boolean,
    canLike: Boolean,
    isLiked: Boolean,
    onLikeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnLikeClick by rememberUpdatedState(onLikeClick)
    val currentIsLiked by rememberUpdatedState(isLiked)
    var previewRequest by remember(trackId) { mutableStateOf(0) }
    var previewVisible by remember(trackId) { mutableStateOf(false) }
    var previewIsLiked by remember(trackId) { mutableStateOf(isLiked) }

    LaunchedEffect(isLiked) {
        previewIsLiked = isLiked
    }
    LaunchedEffect(previewRequest) {
        if (previewRequest == 0) return@LaunchedEffect
        previewVisible = true
        delay(800L)
        previewVisible = false
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 12.dp),
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 390.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .pointerInput(trackId, canLike) {
                    detectTapGestures(
                        onPress = {
                            if (canLike) {
                                previewIsLiked = currentIsLiked
                                previewRequest += 1
                            }
                            tryAwaitRelease()
                        },
                        onDoubleTap = {
                            if (canLike) {
                                previewIsLiked = !currentIsLiked
                                previewRequest += 1
                                currentOnLikeClick()
                            }
                        },
                    )
                },
        ) {
            if (cover != null) {
                Image(
                    bitmap = cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .clip(RoundedCornerShape(16.dp)),
                )
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(DwijColors.PlayerCoverPlaceholderBackground),
                ) {
                    Image(
                        painter = painterResource(Res.drawable.ic_home_player_placeholder),
                        contentDescription = null,
                        modifier = Modifier.size(116.dp),
                    )
                    if (isWaveLoading) {
                        CircularProgressIndicator(
                            color = DwijColors.Pink,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(54.dp),
                        )
                    }
                }
            }
            GlitchCoverFrame(modifier = Modifier.fillMaxSize())
            if (canLike) {
                PlayerHeartPreview(
                    isLiked = previewIsLiked,
                    visible = previewVisible,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxSize(0.48f),
                )
            }
        }
    }
}

/** Рисует масштабируемый контур без тяжёлого полноэкранного векторного ресурса. */
@Composable
private fun GlitchCoverFrame(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val radius = 17.dp.toPx()
        val inset = 5.dp.toPx()
        drawRoundRect(
            color = DwijColors.Cyan.copy(alpha = 0.72f),
            topLeft = Offset(inset + 2.dp.toPx(), inset - 1.dp.toPx()),
            size = Size(size.width - inset * 2, size.height - inset * 2),
            cornerRadius = CornerRadius(radius),
            style = Stroke(width = 1.dp.toPx()),
        )
        drawRoundRect(
            color = DwijColors.Pink.copy(alpha = 0.9f),
            topLeft = Offset(inset - 2.dp.toPx(), inset + 1.dp.toPx()),
            size = Size(size.width - inset * 2, size.height - inset * 2),
            cornerRadius = CornerRadius(radius),
            style = Stroke(width = 1.5.dp.toPx()),
        )
        val whiteStroke = 1.dp.toPx()
        drawLine(DwijColors.White,
            Offset(size.width * 0.08f, inset),
            Offset(size.width * 0.38f, inset), whiteStroke)
        drawLine(DwijColors.Pink,
            Offset(size.width * 0.66f, inset - 3.dp.toPx()),
            Offset(size.width * 0.91f, inset - 3.dp.toPx()), 2.dp.toPx())
        drawLine(DwijColors.Cyan,
            Offset(inset - 3.dp.toPx(), size.height * 0.72f),
            Offset(inset - 3.dp.toPx(), size.height * 0.92f), 2.dp.toPx())
        drawLine(DwijColors.White,
            Offset(size.width * 0.69f, size.height - inset),
            Offset(size.width * 0.93f, size.height - inset), whiteStroke)
    }
}

/** Рисует большое состояние лайка и анимирует его короткое появление поверх обложки. */
@Composable
private fun PlayerHeartPreview(
    isLiked: Boolean,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val previewAlpha by animateFloatAsState(
        targetValue = when {
            !visible -> 0f
            isLiked -> 0.96f
            else -> 0.38f
        },
        animationSpec = tween(if (visible) 70 else 280),
        label = "playerHeartAlpha",
    )
    val previewScale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.78f,
        animationSpec = tween(if (visible) 90 else 280),
        label = "playerHeartScale",
    )

    Canvas(
        modifier = modifier.graphicsLayer {
            alpha = previewAlpha
            scaleX = previewScale
            scaleY = previewScale
        },
    ) {
        val heartWidth = size.width
        val heartHeight = size.height
        val heart = Path().apply {
            moveTo(heartWidth * 0.5f, heartHeight * 0.88f)
            cubicTo(heartWidth * 0.42f, heartHeight * 0.77f, heartWidth * 0.1f, heartHeight * 0.58f, heartWidth * 0.1f, heartHeight * 0.32f)
            cubicTo(heartWidth * 0.1f, heartHeight * 0.08f, heartWidth * 0.38f, heartHeight * 0.02f, heartWidth * 0.5f, heartHeight * 0.23f)
            cubicTo(heartWidth * 0.62f, heartHeight * 0.02f, heartWidth * 0.9f, heartHeight * 0.08f, heartWidth * 0.9f, heartHeight * 0.32f)
            cubicTo(heartWidth * 0.9f, heartHeight * 0.58f, heartWidth * 0.58f, heartHeight * 0.77f, heartWidth * 0.5f, heartHeight * 0.88f)
            close()
        }
        translate(left = 3.dp.toPx()) {
            drawPath(heart, DwijColors.Cyan.copy(alpha = 0.55f))
        }
        translate(left = -3.dp.toPx()) {
            drawPath(heart, DwijColors.Pink.copy(alpha = 0.7f))
        }
        drawPath(
            path = heart,
            color = if (isLiked) DwijColors.Pink else DwijColors.White.copy(alpha = 0.82f),
        )
        drawPath(
            path = heart,
            color = DwijColors.White.copy(alpha = if (isLiked) 0.72f else 0.9f),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

/** Рисует источник, название, автора и альбом текущего трека. */
@Composable
private fun FullPlayerMetadata(
    title: String,
    artist: String,
    album: String?,
    sourceLabel: String?,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    ) {
//        sourceLabel?.let { source ->
//            Text(
//                text = source,
//                color = DwijColors.Cyan,
//                fontSize = 9.sp,
//                fontWeight = FontWeight.Bold,
//                letterSpacing = 1.1.sp,
//                modifier = Modifier
//                    .border(1.dp, DwijColors.Cyan.copy(alpha = 0.45f), RoundedCornerShape(3.dp))
//                    .padding(horizontal = 8.dp, vertical = 3.dp),
//            )
//        }
        Text(
            text = title,
            color = DwijColors.White,
            fontSize = 27.sp,
            lineHeight = 31.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = artist,
            color = DwijColors.PlayerArtistText,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Изолирует часто меняющуюся позицию трека от остального визуального дерева. */
@Composable
private fun FullPlayerProgress(
    trackId: String?,
    currentPositionMillis: Long,
    durationMillis: Long,
    onSeek: (Long) -> Unit,
) {
    val volumeControl =
        LocalPlayerVolumeControl.current

    val safeDuration =
        durationMillis.coerceAtLeast(0L)

    val safePosition =
        currentPositionMillis.coerceIn(
            0L,
            safeDuration,
        )

    var draggedPositionMillis by
    remember(trackId) {
        mutableStateOf<Long?>(
            null,
        )
    }

    val displayedPosition =
        draggedPositionMillis
            ?: safePosition

    val sliderMaximum =
        safeDuration
            .coerceAtLeast(1L)
            .toFloat()

    val progressFraction =
        if (safeDuration > 0L) {
            (
                    displayedPosition.toFloat() /
                            safeDuration.toFloat()
                    )
                .coerceIn(
                    0f,
                    1f,
                )
        } else {
            0f
        }

    Row(
        verticalAlignment =
            Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = 24.dp,
                vertical = 6.dp,
            ),
    ) {
        Column(
            modifier = Modifier
                .weight(1f),
        ) {
            PlayerWaveformSlider(
                value =
                    displayedPosition
                        .coerceAtMost(
                            safeDuration,
                        )
                        .toFloat(),
                valueRange =
                    0f..sliderMaximum,
                progressFraction =
                    progressFraction,
                enabled =
                    safeDuration > 0L,
                onValueChange = { value ->
                    draggedPositionMillis =
                        value.toLong()
                },
                onValueChangeFinished = {
                    draggedPositionMillis
                        ?.let(onSeek)

                    draggedPositionMillis =
                        null
                },
            )

            Row(
                modifier =
                    Modifier.fillMaxWidth(),
            ) {
                Text(
                    text =
                        formatFullPlayerTime(
                            displayedPosition,
                        ),
                    color =
                        DwijColors.Cyan,
                    fontSize =
                        10.sp,
                )

                Spacer(
                    modifier =
                        Modifier.weight(1f),
                )

                Text(
                    text =
                        formatFullPlayerTime(
                            safeDuration,
                        ),
                    color =
                        DwijColors.SecondaryText,
                    fontSize =
                        10.sp,
                )
            }
        }

        /*
         * На Android LocalPlayerVolumeControl == null,
         * поэтому ни кнопка, ни занимаемое ей место
         * вообще не попадают в layout.
         */
        if (volumeControl != null) {
            val volume by
            volumeControl.volume
                .collectAsState()

            Spacer(
                modifier =
                    Modifier.width(7.dp),
            )

            PlayerVolumeButton(
                volume =
                    volume,
                onVolumeChange =
                    volumeControl::setVolume,
            )
        }
    }
}

/**
 * Desktop-only кнопка громкости.
 *
 * Сам composable платформенно независим, но вызывается только когда
 * LocalPlayerVolumeControl.current != null.
 */
@Composable
private fun PlayerVolumeButton(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
) {
    var popupVisible by
    remember {
        mutableStateOf(
            false,
        )
    }

    val popupHeight =
        154.dp

    val popupGap =
        7.dp

    val density =
        LocalDensity.current

    val popupOffsetY =
        with(density) {
            -(
                    popupHeight +
                            popupGap
                    ).roundToPx()
        }

    val volumeDescription =
        stringResource(
            Res.string.player_volume_content_description,
        )

    Box(
        contentAlignment =
            Alignment.Center,
        modifier =
            Modifier.size(34.dp),
    ) {
        IconButton(
            onClick = {
                popupVisible =
                    !popupVisible
            },
            modifier =
                Modifier.size(34.dp),
        ) {
            Image(
                painter =
                    painterResource(
                        Res.drawable.ic_player_volume,
                    ),
                contentDescription =
                    volumeDescription,
                contentScale =
                    ContentScale.Fit,
                modifier =
                    Modifier.size(25.dp),
            )
        }

        if (popupVisible) {
            Popup(
                alignment =
                    Alignment.TopCenter,
                offset =
                    IntOffset(
                        x = 0,
                        y = popupOffsetY,
                    ),
                onDismissRequest = {
                    popupVisible =
                        false
                },
                /*
                 * focusable нужен в том числе для нормальной
                 * desktop-клавиатурной обработки и Escape.
                 *
                 * dismissOnClickOutside по умолчанию true.
                 */
                properties =
                    PopupProperties(
                        focusable = true,
                    ),
            ) {
                PlayerVolumePopup(
                    volume =
                        volume,
                    onVolumeChange =
                        onVolumeChange,
                    modifier =
                        Modifier.height(
                            popupHeight,
                        ),
                )
            }
        }
    }
}

/**
 * Вертикальный регулятор громкости.
 *
 * Используется обычный Slider, развёрнутый вертикально:
 * значение 0 находится снизу, 1 — сверху.
 */
@Composable
private fun PlayerVolumePopup(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape =
        RoundedCornerShape(
            percent = 50,
        )

    Box(
        contentAlignment =
            Alignment.Center,
        modifier = modifier
            .width(52.dp)
            .clip(shape)
            .background(
                DwijColors
                    .PlayerMainControlBackground,
            )
            .border(
                width = 1.dp,
                color = DwijColors.Pink
                    .copy(
                        alpha = 0.72f,
                    ),
                shape = shape,
            ),
    ) {
        /*
         * Slider остаётся нормальным Material Slider:
         * мышь, drag и accessibility работают штатно.
         *
         * Layout у него горизонтальный 116x40,
         * а graphicsLayer визуально разворачивает его
         * в вертикальный 40x116.
         */
        Slider(
            value =
                volume.coerceIn(
                    0f,
                    1f,
                ),
            onValueChange = {
                onVolumeChange(
                    it.coerceIn(
                        0f,
                        1f,
                    ),
                )
            },
            valueRange =
                0f..1f,
            colors =
                SliderDefaults.colors(
                    thumbColor =
                        DwijColors.White,
                    activeTrackColor =
                        DwijColors.Pink,
                    inactiveTrackColor =
                        DwijColors.Cyan
                            .copy(
                                alpha = 0.28f,
                            ),
                ),
            modifier = Modifier
                .requiredSize(
                    width = 116.dp,
                    height = 40.dp,
                )
                .graphicsLayer {
                    /*
                     * -90 градусов:
                     * минимум снизу,
                     * максимум сверху.
                     */
                    rotationZ =
                        -90f
                },
        )
    }
}

/**
 * Накладывает один waveform PNG двумя tint-слоями и оставляет прозрачный Material Slider
 * только для обработки drag-жеста, семантики и клавиатурного управления.
 */
@Composable
private fun PlayerWaveformSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    progressFraction: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    val waveformPainter = painterResource(Res.drawable.ic_player_waveform)
    val headWidth = 12.dp

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp),
    ) {
        Image(
            painter = waveformPainter,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(DwijColors.Cyan.copy(alpha = 0.24f)),
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 3.dp),
        )
        Image(
            painter = waveformPainter,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            colorFilter = ColorFilter.tint(DwijColors.Pink),
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 3.dp)
                .drawWithContent {
                    clipRect(right = size.width * progressFraction) {
                        this@drawWithContent.drawContent()
                    }
                },
        )
        Image(
            painter = painterResource(Res.drawable.ic_player_waveform_head),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            alpha = if (enabled) 1f else 0.35f,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = (maxWidth - headWidth) * progressFraction)
                .width(headWidth)
                .height(22.dp),
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            enabled = enabled,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = DwijColors.Transparent,
                activeTrackColor = DwijColors.Transparent,
                inactiveTrackColor = DwijColors.Transparent,
                disabledThumbColor = DwijColors.Transparent,
                disabledActiveTrackColor = DwijColors.Transparent,
                disabledInactiveTrackColor = DwijColors.Transparent,
            ),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Compose-аналог прежнего FlexboxLayoutManager: теги занимают свою естественную ширину
 * и автоматически переносятся на следующую строку.
 */
@Composable
private fun PlayerPlaylistMemberships(
    playlistTitles: List<String>,
    onAddToPlaylistClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
    ) {
        PlayerWrapRow(
            horizontalSpacing = 7.dp,
            verticalSpacing = 7.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            playlistTitles.forEach { title ->
                PlayerPlaylistChip(
                    title = title,
                    onClick = onAddToPlaylistClick,
                )
            }
            PlayerPlaylistChip(
                title = stringResource(Res.string.player_add_to_playlist),
                accent = DwijColors.Pink,
                onClick = onAddToPlaylistClick,
            )
        }
    }
}

/**
 * Стабильная замена экспериментальному Foundation FlowRow.
 * Измеряет плашки, переносит не поместившиеся и центрирует каждую получившуюся строку.
 */
@Composable
private fun PlayerWrapRow(
    horizontalSpacing: Dp,
    verticalSpacing: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        content = content,
        modifier = modifier,
    ) { measurables, constraints ->
        val horizontalSpacingPx = horizontalSpacing.roundToPx()
        val verticalSpacingPx = verticalSpacing.roundToPx()
        val childConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { measurable -> measurable.measure(childConstraints) }
        val availableWidth = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            placeables.sumOf { it.width } +
                horizontalSpacingPx * (placeables.size - 1).coerceAtLeast(0)
        }
        val rows = mutableListOf<MutableList<Placeable>>()
        val rowWidths = mutableListOf<Int>()
        val rowHeights = mutableListOf<Int>()

        placeables.forEach { placeable ->
            val currentRow = rows.lastOrNull()
            val currentWidth = rowWidths.lastOrNull() ?: 0
            val proposedWidth = if (currentRow.isNullOrEmpty()) {
                placeable.width
            } else {
                currentWidth + horizontalSpacingPx + placeable.width
            }
            if (currentRow == null || proposedWidth > availableWidth) {
                rows += mutableListOf(placeable)
                rowWidths += placeable.width
                rowHeights += placeable.height
            } else {
                currentRow += placeable
                rowWidths[rowWidths.lastIndex] = proposedWidth
                rowHeights[rowHeights.lastIndex] = maxOf(
                    rowHeights.last(),
                    placeable.height,
                )
            }
        }

        val desiredWidth = if (constraints.hasBoundedWidth) {
            availableWidth
        } else {
            rowWidths.maxOrNull() ?: 0
        }
        val desiredHeight = rowHeights.sum() +
            verticalSpacingPx * (rows.size - 1).coerceAtLeast(0)
        val layoutWidth = constraints.constrainWidth(desiredWidth)
        val layoutHeight = constraints.constrainHeight(desiredHeight)

        layout(layoutWidth, layoutHeight) {
            var y = 0
            rows.forEachIndexed { rowIndex, row ->
                var x = ((layoutWidth - rowWidths[rowIndex]) / 2).coerceAtLeast(0)
                val rowHeight = rowHeights[rowIndex]
                row.forEach { placeable ->
                    placeable.placeRelative(
                        x = x,
                        y = y + (rowHeight - placeable.height) / 2,
                    )
                    x += placeable.width + horizontalSpacingPx
                }
                y += rowHeight + verticalSpacingPx
            }
        }
    }
}

/** Рисует один стабильный по цвету тег плейлиста. */
@Composable
private fun PlayerPlaylistChip(
    title: String,
    onClick: () -> Unit,
    accent: Color = playlistAccent(title),
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.16f))
            .border(1.dp, accent.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Text(
            text = title,
            color = DwijColors.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** Рисует основные команды и скрывает shuffle/repeat для очередей, где режимы запрещены. */
@Composable
private fun FullPlayerControls(
    enabled: Boolean,
    isPlaying: Boolean,
    isShuffle: Boolean,
    isRepeatAll: Boolean,
    showPlaybackModes: Boolean,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onRepeatClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        if (showPlaybackModes) {
            PlayerModeControl(
                mode = PlayerModeIcon.Shuffle,
                contentDescription = stringResource(Res.string.player_shuffle_content_description),
                selected = isShuffle,
                enabled = enabled,
                onClick = onShuffleClick,
            )
        } else {
            Spacer(modifier = Modifier.size(46.dp))
        }
        PlayerSmallControl(
            icon = Res.drawable.ic_home_player_next,
            contentDescription = stringResource(Res.string.home_player_previous_content_description),
            enabled = enabled,
            mirror = true,
            onClick = onPreviousClick,
        )
        PlayerMainControl(
            isPlaying = isPlaying,
            enabled = enabled,
            onClick = onPlayPauseClick,
        )
        PlayerSmallControl(
            icon = Res.drawable.ic_home_player_next,
            contentDescription = stringResource(Res.string.home_player_next_content_description),
            enabled = enabled,
            onClick = onNextClick,
        )
        if (showPlaybackModes) {
            PlayerModeControl(
                mode = PlayerModeIcon.Repeat,
                contentDescription = stringResource(Res.string.player_repeat_content_description),
                selected = isRepeatAll,
                enabled = enabled,
                onClick = onRepeatClick,
            )
        } else {
            Spacer(modifier = Modifier.size(46.dp))
        }
    }
}

/** Тип компактной режимной пиктограммы, которую рисуем без старых растровых кнопок. */
private enum class PlayerModeIcon {
    Shuffle,
    Repeat,
}

/** Рисует shuffle/repeat тонкими глич-контурами и меняет основной цвет выбранного режима. */
@Composable
private fun PlayerModeControl(
    mode: PlayerModeIcon,
    contentDescription: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(46.dp),
    ) {
        Canvas(
            modifier = Modifier
                .size(27.dp)
                .semantics { this.contentDescription = contentDescription },
        ) {
            val primary = when {
                !enabled -> DwijColors.SecondaryText.copy(alpha = 0.35f)
                selected -> DwijColors.Pink
                else -> DwijColors.White
            }
            val stroke = 1.8.dp.toPx()
            val glitchStroke = 1.4.dp.toPx()

            fun drawMode(color: Color, horizontalOffset: Float, strokeWidth: Float) {
                val unit = size.minDimension / 24f
                fun point(x: Float, y: Float) = Offset(
                    x = x * unit + horizontalOffset,
                    y = y * unit,
                )
                if (mode == PlayerModeIcon.Shuffle) {
                    val upper = Path().apply {
                        moveTo(point(2f, 6f).x, point(2f, 6f).y)
                        lineTo(point(7f, 6f).x, point(7f, 6f).y)
                        lineTo(point(16f, 18f).x, point(16f, 18f).y)
                        lineTo(point(21f, 18f).x, point(21f, 18f).y)
                    }
                    val lower = Path().apply {
                        moveTo(point(2f, 18f).x, point(2f, 18f).y)
                        lineTo(point(7f, 18f).x, point(7f, 18f).y)
                        lineTo(point(16f, 6f).x, point(16f, 6f).y)
                        lineTo(point(21f, 6f).x, point(21f, 6f).y)
                    }
                    drawPath(upper, color, style = Stroke(strokeWidth, cap = StrokeCap.Square))
                    drawPath(lower, color, style = Stroke(strokeWidth, cap = StrokeCap.Square))
                    drawLine(color, point(18f, 3f), point(21f, 6f), strokeWidth, StrokeCap.Square)
                    drawLine(color, point(21f, 6f), point(18f, 9f), strokeWidth, StrokeCap.Square)
                    drawLine(color, point(18f, 15f), point(21f, 18f), strokeWidth, StrokeCap.Square)
                    drawLine(color, point(21f, 18f), point(18f, 21f), strokeWidth, StrokeCap.Square)
                } else {
                    val top = Path().apply {
                        moveTo(point(4f, 10f).x, point(4f, 10f).y)
                        lineTo(point(4f, 7f).x, point(4f, 7f).y)
                        lineTo(point(20f, 7f).x, point(20f, 7f).y)
                        lineTo(point(20f, 11f).x, point(20f, 11f).y)
                    }
                    val bottom = Path().apply {
                        moveTo(point(20f, 14f).x, point(20f, 14f).y)
                        lineTo(point(20f, 17f).x, point(20f, 17f).y)
                        lineTo(point(4f, 17f).x, point(4f, 17f).y)
                        lineTo(point(4f, 13f).x, point(4f, 13f).y)
                    }
                    drawPath(top, color, style = Stroke(strokeWidth, cap = StrokeCap.Square))
                    drawPath(bottom, color, style = Stroke(strokeWidth, cap = StrokeCap.Square))
                    drawLine(color, point(17f, 8f), point(20f, 11f), strokeWidth, StrokeCap.Square)
                    drawLine(color, point(20f, 11f), point(23f, 8f), strokeWidth, StrokeCap.Square)
                    drawLine(color, point(7f, 16f), point(4f, 13f), strokeWidth, StrokeCap.Square)
                    drawLine(color, point(4f, 13f), point(1f, 16f), strokeWidth, StrokeCap.Square)
                }
            }

            drawMode(DwijColors.Cyan.copy(alpha = 0.5f), 1.dp.toPx(), glitchStroke)
            drawMode(DwijColors.Pink.copy(alpha = 0.55f), -1.dp.toPx(), glitchStroke)
            drawMode(primary, 0f, stroke)
        }
    }
}

/** Маленькая транспортная кнопка с цветом состояния вместо Material-заливки. */
@Composable
private fun PlayerSmallControl(
    icon: DrawableResource,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    mirror: Boolean = false,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(46.dp),
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            colorFilter = ColorFilter.tint(
                if (enabled) DwijColors.White else DwijColors.SecondaryText.copy(alpha = 0.35f),
            ),
            modifier = Modifier
                .size(27.dp)
                .graphicsLayer { if (mirror) scaleX = -1f },
        )
    }
}

/** Использует ту же глич-графику play/pause, что и остальные новые экраны. */
@Composable
private fun PlayerMainControl(
    isPlaying: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(86.dp)
            .clip(CircleShape)
            .background(DwijColors.PlayerMainControlBackground)
            .border(1.dp, DwijColors.Pink.copy(alpha = if (enabled) 0.9f else 0.35f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Image(
            painter = painterResource(
                if (isPlaying) {
                    Res.drawable.ic_player_pause_v2
                } else {
                    Res.drawable.ic_player_play_v2
                },
            ),
            contentDescription = stringResource(
                if (isPlaying) {
                    Res.string.home_player_pause_content_description
                } else {
                    Res.string.home_player_play_content_description
                },
            ),
            contentScale = ContentScale.FillBounds,
            alpha = if (enabled) 1f else 0.4f,
            modifier = Modifier.requiredSize(width = 255.dp, height = 170.dp),
        )
    }
}

/** Форматирует миллисекунды без зависимости UI от Android DateUtils. */
private fun formatFullPlayerTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%d:%02d", minutes, seconds)
}

/** Возвращает один и тот же акцент для одинакового названия между рекомпозициями. */
private fun playlistAccent(title: String): Color {
    val accents = listOf(
        DwijColors.Pink,
        DwijColors.Cyan,
        DwijColors.PlayerPlaylistPurple,
        DwijColors.PlayerPlaylistOrange,
    )
    return accents[Math.floorMod(title.hashCode(), accents.size)]
}

/** Android/mobile-вариант: управление громкостью платформой не предоставлено. */
@Preview(
    name = "Full player — no volume",
    widthDp = 360,
    heightDp = 800,
    showBackground = true,
    backgroundColor = DwijColors.BackgroundArgb,
)
@Composable
private fun FullPlayerScreenPreviewNoVolume() {
    FullPlayerPreviewContent()
}

/** Desktop-вариант: доступно управление громкостью приложения. */
@Preview(
    name = "Full player — desktop volume",
    widthDp = 520,
    heightDp = 900,
    showBackground = true,
    backgroundColor = DwijColors.BackgroundArgb,
)
@Composable
private fun FullPlayerScreenPreviewWithVolume() {
    val volumeState =
        remember {
            MutableStateFlow(
                0.68f,
            )
        }

    val volumeControl =
        remember {
            object : PlayerVolumeControl {

                override val volume =
                    volumeState

                override fun setVolume(
                    volume: Float,
                ) {
                    volumeState.value =
                        volume.coerceIn(
                            0f,
                            1f,
                        )
                }

                override fun toggleMute() {
                    volumeState.value =
                        if (
                            volumeState.value > 0f
                        ) {
                            0f
                        } else {
                            0.68f
                        }
                }
            }
        }

    CompositionLocalProvider(
        LocalPlayerVolumeControl provides
                volumeControl,
    ) {
        FullPlayerPreviewContent()
    }
}

/** Общие данные обоих preview, чтобы мобильный и desktop варианты не разъезжались. */
@Composable
private fun FullPlayerPreviewContent() {
    FullPlayerScreen(
        state = FullPlayerUiState(
            trackId = "preview",
            queueTitle = "Ночной движ",
            queuePosition = 4,
            title = "Ночной город",
            artist = "Три дня дождя",
            album = "Когда ты откроешь глаза",
            sourceLabel = "ЯНДЕКС МУЗЫКА",
            hasMultipleSources = true,
            hasUnresolvedMatchCandidate = false,
            cover = null,
            isPlaying = true,
            currentPositionMillis = 84_000L,
            durationMillis = 225_000L,
            isShuffle = false,
            isRepeatAll = true,
            showPlaybackModes = true,
            canLike = true,
            isLiked = true,
            playlistTitles = listOf(
                "В дорогу",
                "Ночное",
                "Любимое новое",
            ),
        ),
        playerEvents = emptyFlow(),
        uiMessages = emptyFlow(),
        onBackClick = {},
        onPlayPauseClick = {},
        onPreviousClick = {},
        onNextClick = {},
        onSeek = {},
        onShuffleClick = {},
        onRepeatClick = {},
        onLikeClick = {},
        onAddToPlaylistClick = {},
        onSourcesClick = {},
    )
}
