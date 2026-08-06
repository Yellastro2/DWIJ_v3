package com.yellastrodev.dwij

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yellastrodev.dwij.resources.*
import org.jetbrains.compose.resources.painterResource

/** Один source-инстанс, который пользователь может отметить в диалоге вариантов. */
@Immutable
data class TrackSourceOptionUiModel(
    val instanceId: String,
    val item: TrackListItemUiModel,
    val sourceIndicator: TrackSourceIndicator,
)

/**
 * Показывает найденные source-варианты и локально хранит выбранные строки.
 * Кнопка сохранения появляется для двух и более вариантов; само объединение делегировано экрану,
 * чтобы диалог оставался UI-компонентом и только отображал процесс или ошибку операции.
 */
@Composable
fun MultiSourceDialog(
    options: List<TrackSourceOptionUiModel>,
    loadCover: suspend (instanceId: String) -> ImageBitmap?,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
    isSaving: Boolean = false,
    errorMessage: String? = null,
) {
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    val coverStates = remember { mutableMapOf<String, TrackCoverState>() }
    val errorSnackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(options) {
        val availableIds = options.mapTo(mutableSetOf(), TrackSourceOptionUiModel::instanceId)
        selectedIds = selectedIds.intersect(availableIds)
    }
    LaunchedEffect(errorMessage) {
        errorMessage?.let { message -> errorSnackbarHostState.showSnackbar(message) }
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
                .background(MultiSourceBackground)
                .paint(
                    painter = painterResource(Res.drawable.bg_player_glitch_v2),
                    sizeToIntrinsics = false,
                    contentScale = ContentScale.Crop,
                    alpha = 0.2f,
                ),
        ) {
            Column(modifier = Modifier.padding(vertical = 18.dp)) {
                Text(
                    text = stringResource(R.string.multi_source_dialog_title),
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                )
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .heightIn(max = 390.dp),
                ) {
                    if (options.isEmpty()) {
                        item(key = "multi_source_empty") {
                            Text(
                                text = stringResource(R.string.multi_source_dialog_empty),
                                color = MultiSourceSecondary,
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 24.dp),
                            )
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
                            TrackListItem(
                                item = option.item.copy(
                                    hasMultipleSources = false,
                                    hasUnresolvedMatchCandidate = false,
                                ),
                                coverState = coverState,
                                sourceIndicator = option.sourceIndicator,
                                isSelected = option.instanceId in selectedIds,
                                onClick = {
                                    if (!isSaving) {
                                        selectedIds = if (option.instanceId in selectedIds) {
                                            selectedIds - option.instanceId
                                        } else {
                                            selectedIds + option.instanceId
                                        }
                                    }
                                },
                                modifier = Modifier.background(Color.Black.copy(alpha = 0.16f)),
                            )
                        }
                    }
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
                            text = stringResource(R.string.multi_source_dialog_cancel),
                            color = MultiSourceSecondary,
                        )
                    }
                    if (selectedIds.size > 1) {
                        TextButton(
                            onClick = { onSave(selectedIds) },
                            enabled = !isSaving,
                        ) {
                            Text(
                                text = stringResource(
                                    if (isSaving) {
                                        R.string.multi_source_dialog_saving
                                    } else {
                                        R.string.multi_source_dialog_save
                                    },
                                ),
                                color = MultiSourcePink,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF03040F)
@Composable
private fun MultiSourceDialogPreview() {
    MultiSourceDialog(
        options = listOf(
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
        loadCover = { null },
        onDismiss = {},
        onSave = {},
    )
}

private val MultiSourceBackground = Color(0xFF080A16)
private val MultiSourceSecondary = Color(0xFFA7AABC)
private val MultiSourcePink = Color(0xFFFF00BF)
