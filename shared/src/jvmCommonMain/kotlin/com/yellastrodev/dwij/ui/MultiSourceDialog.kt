package com.yellastrodev.dwij.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.zIndex
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yellastrodev.dwij.TrackListItemUiModel
import com.yellastrodev.dwij.resources.*
import com.yellastrodev.dwij.ui.theme.DwijColors
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Один source-инстанс, который пользователь может отметить в диалоге вариантов. */
@Immutable
data class TrackSourceOptionUiModel(
    val instanceId: String,
    val item: TrackListItemUiModel,
    val sourceIndicator: TrackSourceIndicator,
    val isPlayable: Boolean = true,
)

/**
 * Управляет уже подтверждёнными source-инстансами и отдельно показывает кандидатов на merge.
 * Первая проигрываемая строка становится preferred-инстансом после завершения перетаскивания;
 * недоступные и незакэшированные Яндекс-инстансы закреплены в конце подтверждённого списка.
 */
@Composable
fun MultiSourceDialog(
    confirmedOptions: List<TrackSourceOptionUiModel>,
    candidateOptions: List<TrackSourceOptionUiModel>,
    preferredInstanceId: String?,
    loadCover: suspend (instanceId: String) -> ImageBitmap?,
    onDismiss: () -> Unit,
    onPreferredInstanceChange: (String) -> Unit,
    onSaveCandidates: (Set<String>) -> Unit,
    manageConfirmedSources: Boolean = true,
    minimumCandidateSelectionCount: Int = 1,
    isSaving: Boolean = false,
    errorMessage: String? = null,
) {
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var orderedConfirmed by remember(confirmedOptions, preferredInstanceId) {
        mutableStateOf(
            confirmedOptions
                .filter(TrackSourceOptionUiModel::isPlayable)
                .sortedByDescending { option -> option.instanceId == preferredInstanceId } +
                confirmedOptions.filterNot(TrackSourceOptionUiModel::isPlayable),
        )
    }
    var committedPreferredId by remember(confirmedOptions, preferredInstanceId) {
        mutableStateOf(preferredInstanceId ?: orderedConfirmed.firstOrNull()?.instanceId)
    }
    var draggedInstanceId by remember { mutableStateOf<String?>(null) }
    var draggedDistancePx by remember { mutableStateOf(0f) }
    val coverStates = remember { mutableMapOf<String, TrackCoverState>() }
    val errorSnackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(candidateOptions) {
        val availableIds = candidateOptions
            .mapTo(mutableSetOf(), TrackSourceOptionUiModel::instanceId)
        selectedIds = selectedIds.intersect(availableIds)
    }
    LaunchedEffect(errorMessage) {
        errorMessage?.let { message -> errorSnackbarHostState.showSnackbar(message) }
    }

    fun commitPreferredInstance() {
        val firstInstanceId = orderedConfirmed
            .firstOrNull(TrackSourceOptionUiModel::isPlayable)
            ?.instanceId
            ?: return
        if (firstInstanceId != committedPreferredId) {
            committedPreferredId = firstInstanceId
            onPreferredInstanceChange(firstInstanceId)
        }
    }

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isSaving,
            dismissOnClickOutside = !isSaving,
        ),
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 18.dp)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
            .background(DwijColors.MultiSourceBackground)
                .paint(
                    painter = painterResource(Res.drawable.bg_player_glitch_v2),
                    sizeToIntrinsics = false,
                    contentScale = ContentScale.Crop,
                    alpha = 0.2f,
                ),
        ) {
            Column(modifier = Modifier.padding(vertical = 18.dp)) {
                Text(
                    text = stringResource(Res.string.multi_source_dialog_title),
                    color = DwijColors.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                )
                if (!manageConfirmedSources) {
                    CandidateSourcesList(
                        options = candidateOptions,
                        selectedIds = selectedIds,
                        coverStates = coverStates,
                        loadCover = loadCover,
                        isSaving = isSaving,
                        onToggle = { instanceId ->
                            selectedIds = if (instanceId in selectedIds) {
                                selectedIds - instanceId
                            } else {
                                selectedIds + instanceId
                            }
                        },
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .heightIn(max = 390.dp),
                    )
                } else if (candidateOptions.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .height(390.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.multi_source_confirmed_title),
                            color = DwijColors.SecondaryText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp),
                        )
                        ConfirmedSourcesList(
                            options = orderedConfirmed,
                            coverStates = coverStates,
                            loadCover = loadCover,
                            isSaving = isSaving,
                            draggedInstanceId = draggedInstanceId,
                            draggedDistancePx = draggedDistancePx,
                            onDragStart = { instanceId ->
                                draggedInstanceId = instanceId
                                draggedDistancePx = 0f
                            },
                            onDrag = { instanceId, deltaY, itemHeightPx ->
                                if (draggedInstanceId != instanceId) return@ConfirmedSourcesList
                                draggedDistancePx += deltaY
                                val currentIndex = orderedConfirmed.indexOfFirst { option ->
                                    option.instanceId == instanceId
                                }
                                val lastPlayableIndex = orderedConfirmed
                                    .indexOfLast(TrackSourceOptionUiModel::isPlayable)
                                val moveDown = draggedDistancePx >= itemHeightPx / 2f &&
                                    currentIndex in 0 until lastPlayableIndex
                                val moveUp = draggedDistancePx <= -itemHeightPx / 2f &&
                                    currentIndex > 0
                                if (moveDown || moveUp) {
                                    val targetIndex = currentIndex + if (moveDown) 1 else -1
                                    orderedConfirmed = orderedConfirmed.toMutableList().apply {
                                        add(targetIndex, removeAt(currentIndex))
                                    }
                                    draggedDistancePx += if (moveDown) -itemHeightPx else itemHeightPx
                                }
                            },
                            onDragFinished = {
                                draggedInstanceId = null
                                draggedDistancePx = 0f
                                commitPreferredInstance()
                            },
                            modifier = Modifier.weight(1f),
                        )
                        HorizontalDivider(color = DwijColors.White.copy(alpha = 0.12f))
                        Text(
                            text = stringResource(Res.string.multi_source_candidates_title),
                            color = DwijColors.SecondaryText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp),
                        )
                        CandidateSourcesList(
                            options = candidateOptions,
                            selectedIds = selectedIds,
                            coverStates = coverStates,
                            loadCover = loadCover,
                            isSaving = isSaving,
                            onToggle = { instanceId ->
                                selectedIds = if (instanceId in selectedIds) {
                                    selectedIds - instanceId
                                } else {
                                    selectedIds + instanceId
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Text(
                        text = stringResource(Res.string.multi_source_confirmed_title),
                        color = DwijColors.SecondaryText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp),
                    )
                    ConfirmedSourcesList(
                        options = orderedConfirmed,
                        coverStates = coverStates,
                        loadCover = loadCover,
                        isSaving = isSaving,
                        draggedInstanceId = draggedInstanceId,
                        draggedDistancePx = draggedDistancePx,
                        onDragStart = { instanceId ->
                            draggedInstanceId = instanceId
                            draggedDistancePx = 0f
                        },
                        onDrag = { instanceId, deltaY, itemHeightPx ->
                            if (draggedInstanceId != instanceId) return@ConfirmedSourcesList
                            draggedDistancePx += deltaY
                            val currentIndex = orderedConfirmed.indexOfFirst { option ->
                                option.instanceId == instanceId
                            }
                            val lastPlayableIndex = orderedConfirmed
                                .indexOfLast(TrackSourceOptionUiModel::isPlayable)
                            val moveDown = draggedDistancePx >= itemHeightPx / 2f &&
                                currentIndex in 0 until lastPlayableIndex
                            val moveUp = draggedDistancePx <= -itemHeightPx / 2f && currentIndex > 0
                            if (moveDown || moveUp) {
                                val targetIndex = currentIndex + if (moveDown) 1 else -1
                                orderedConfirmed = orderedConfirmed.toMutableList().apply {
                                    add(targetIndex, removeAt(currentIndex))
                                }
                                draggedDistancePx += if (moveDown) -itemHeightPx else itemHeightPx
                            }
                        },
                        onDragFinished = {
                            draggedInstanceId = null
                            draggedDistancePx = 0f
                            commitPreferredInstance()
                        },
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .heightIn(max = 390.dp),
                    )
                }
                SnackbarHost(
                    hostState = errorSnackbarHostState,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    TextButton(onClick = onDismiss, enabled = !isSaving) {
                        Text(
                            text = stringResource(Res.string.multi_source_dialog_cancel),
                            color = DwijColors.SecondaryText,
                        )
                    }
                    if (selectedIds.size >= minimumCandidateSelectionCount) {
                        TextButton(
                            onClick = { onSaveCandidates(selectedIds) },
                            enabled = !isSaving,
                        ) {
                            Text(
                                text = stringResource(
                                    if (isSaving) {
                                        Res.string.multi_source_dialog_saving
                                    } else {
                                        Res.string.multi_source_dialog_save
                                    },
                                ),
                                color = DwijColors.Pink,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmedSourcesList(
    options: List<TrackSourceOptionUiModel>,
    coverStates: MutableMap<String, TrackCoverState>,
    loadCover: suspend (instanceId: String) -> ImageBitmap?,
    isSaving: Boolean,
    draggedInstanceId: String?,
    draggedDistancePx: Float,
    onDragStart: (String) -> Unit,
    onDrag: (instanceId: String, deltaY: Float, itemHeightPx: Float) -> Unit,
    onDragFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier,
    ) {
        if (options.isEmpty()) {
            item(key = "multi_source_confirmed_empty") {
                EmptySourcesMessage()
            }
        } else {
            items(
                items = options,
                key = TrackSourceOptionUiModel::instanceId,
            ) { option ->
                val coverState = remember(option.instanceId) {
                    coverStates.getOrPut(option.instanceId) { TrackCoverState() }
                }
                if (option.item.shouldLoadCover) {
                    TrackCoverLoader(
                        trackId = option.instanceId,
                        coverState = coverState,
                        loadCover = loadCover,
                    )
                }
                val isDragged = draggedInstanceId == option.instanceId
                val dragModifier = if (option.isPlayable && !isSaving) {
                    Modifier.pointerInput(option.instanceId, isSaving) {
                        val itemHeightPx = 74.dp.toPx()
                        detectDragGesturesAfterLongPress(
                            onDragStart = { onDragStart(option.instanceId) },
                            onDragCancel = onDragFinished,
                            onDragEnd = onDragFinished,
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(option.instanceId, dragAmount.y, itemHeightPx)
                            },
                        )
                    }
                } else {
                    Modifier
                }
                TrackListItem(
                    item = option.item.copy(
                        isPlaybackBlocked = !option.isPlayable,
                        hasMultipleSources = false,
                        hasUnresolvedMatchCandidate = false,
                    ),
                    coverState = coverState,
                    sourceIndicator = option.sourceIndicator,
                    priorityNumber = options.indexOf(option) + 1,
                    onClick = {},
                    modifier = dragModifier
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragged) draggedDistancePx else 0f
                        }
                        .alpha(if (isSaving) 0.72f else 1f)
                        .background(
                            if (isDragged) {
                                DwijColors.Pink.copy(alpha = 0.18f)
                            } else {
                                DwijColors.Black.copy(alpha = 0.16f)
                            },
                        ),
                )
            }
        }
    }
}

@Composable
private fun CandidateSourcesList(
    options: List<TrackSourceOptionUiModel>,
    selectedIds: Set<String>,
    coverStates: MutableMap<String, TrackCoverState>,
    loadCover: suspend (instanceId: String) -> ImageBitmap?,
    isSaving: Boolean,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier,
    ) {
        items(
            items = options,
            key = TrackSourceOptionUiModel::instanceId,
        ) { option ->
            val coverState = remember(option.instanceId) {
                coverStates.getOrPut(option.instanceId) { TrackCoverState() }
            }
            if (option.item.shouldLoadCover) {
                TrackCoverLoader(
                    trackId = option.instanceId,
                    coverState = coverState,
                    loadCover = loadCover,
                )
            }
            TrackListItem(
                item = option.item.copy(
                    hasMultipleSources = false,
                    hasUnresolvedMatchCandidate = false,
                ),
                coverState = coverState,
                sourceIndicator = option.sourceIndicator,
                isSelected = option.instanceId in selectedIds,
                onClick = { if (!isSaving) onToggle(option.instanceId) },
                modifier = Modifier
                    .alpha(if (isSaving) 0.72f else 1f)
                    .background(DwijColors.Black.copy(alpha = 0.16f)),
            )
        }
    }
}

@Composable
private fun EmptySourcesMessage() {
    Text(
        text = stringResource(Res.string.multi_source_dialog_empty),
        color = DwijColors.SecondaryText,
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 24.dp),
    )
}

@Preview(showBackground = true, backgroundColor = DwijColors.BackgroundArgb)
@Composable
private fun MultiSourceDialogPreview() {
    MultiSourceDialog(
        confirmedOptions = listOf(
            TrackSourceOptionUiModel(
                instanceId = "yandex:1",
                item = TrackListItemUiModel(
                    key = "yandex:1",
                    trackId = "1",
                    title = "Ночной город",
                    artist = "Три дня дождя",
                    shouldLoadCover = false,
                ),
                sourceIndicator = TrackSourceIndicator.YANDEX,
            ),
        ),
        candidateOptions = listOf(
            TrackSourceOptionUiModel(
                instanceId = "local:1",
                item = TrackListItemUiModel(
                    key = "local:1",
                    trackId = "1",
                    title = "Ночной город",
                    artist = "Три дня дождя",
                    shouldLoadCover = false,
                ),
                sourceIndicator = TrackSourceIndicator.LOCAL,
            ),
        ),
        preferredInstanceId = "yandex:1",
        loadCover = { null },
        onDismiss = {},
        onPreferredInstanceChange = {},
        onSaveCandidates = {},
    )
}
