package com.yellastrodev.dwij.ui.playlist

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yellastrodev.dwij.HomeMusicSource
import com.yellastrodev.dwij.resources.*
import com.yellastrodev.dwij.ui.theme.DwijColors
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Неоновый диалог создания плейлиста для выбранного музыкального источника.
 *
 * Локальному плейлисту достаточно названия, а для Яндекса дополнительно доступна настройка
 * публичности. Во время сохранения диалог нельзя закрыть или отправить повторно.
 */
@Composable
fun CreatePlaylistDialog(
    source: HomeMusicSource,
    isCreating: Boolean,
    onDismiss: () -> Unit,
    onCreate: (title: String, isPublic: Boolean) -> Unit,
) {
    var title by rememberSaveable(source) { mutableStateOf("") }
    var isPublic by rememberSaveable(source) { mutableStateOf(false) }
    val normalizedTitle = title.trim()
    val canCreate = normalizedTitle.isNotEmpty() && !isCreating
    val dialogHeight = if (source == HomeMusicSource.Yandex) 344.dp else 278.dp

    Dialog(
        onDismissRequest = { if (!isCreating) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isCreating,
            dismissOnClickOutside = !isCreating,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .widthIn(max = 360.dp)
                .height(dialogHeight)
                .clip(RoundedCornerShape(20.dp))
                .background(DwijColors.CreateDialogBackground)
                .border(
                    width = 1.dp,
                    color = DwijColors.Pink.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(20.dp),
                ),
        ) {
            Image(
                painter = painterResource(Res.drawable.bg_focus_texture),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.22f,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DwijColors.Black.copy(alpha = 0.38f))
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text(
                    text = stringResource(Res.string.playlists_create_dialog_title),
                    color = DwijColors.White,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(
                        if (source == HomeMusicSource.Yandex) {
                            Res.string.home_source_yandex_music
                        } else {
                            Res.string.home_source_local
                        },
                    ),
                    color = DwijColors.CyanBright,
                    fontSize = 12.sp,
                    letterSpacing = 1.1.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(modifier = Modifier.height(16.dp))
                CreatePlaylistNameField(
                    value = title,
                    enabled = !isCreating,
                    onValueChange = { value ->
                        title = value
                            .replace("\n", "")
                            .take(CREATE_PLAYLIST_TITLE_MAX_LENGTH)
                    },
                )
                if (source == HomeMusicSource.Yandex) {
                    Spacer(modifier = Modifier.height(17.dp))
                    CreatePlaylistVisibilityToggle(
                        isPublic = isPublic,
                        enabled = !isCreating,
                        onToggle = { isPublic = !isPublic },
                    )
                }
                Spacer(modifier = Modifier.height(22.dp))
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CreatePlaylistDialogButton(
                        text = stringResource(Res.string.playlists_cancel),
                        accent = DwijColors.CyanBright,
                        enabled = !isCreating,
                        onClick = onDismiss,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    CreatePlaylistDialogButton(
                        text = stringResource(Res.string.playlists_create_confirm),
                        accent = DwijColors.Pink,
                        enabled = canCreate,
                        isLoading = isCreating,
                        onClick = { onCreate(normalizedTitle, isPublic) },
                    )
                }
            }
        }
    }
}

/** Рисует однострочное поле названия без стандартной Material-подложки. */
@Composable
private fun CreatePlaylistNameField(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    Column {
        Text(
            text = stringResource(Res.string.playlists_create_name_label),
            color = DwijColors.CreateDialogLabel,
            fontSize = 12.sp,
        )
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(DwijColors.CreateDialogFieldBackground)
                .border(
                    width = 1.dp,
                    color = if (enabled) {
                        DwijColors.CyanBright.copy(alpha = 0.72f)
                    } else {
                        DwijColors.Disabled
                    },
                    shape = RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 13.dp),
        ) {
            if (value.isEmpty()) {
                Text(
                    text = stringResource(Res.string.playlists_create_name_hint),
                    color = DwijColors.CreateDialogHint,
                    fontSize = 15.sp,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(
                    color = DwijColors.White,
                    fontSize = 16.sp,
                ),
                cursorBrush = SolidColor(DwijColors.Pink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Переключает приватность Яндекс-плейлиста без Material-анимации. */
@Composable
private fun CreatePlaylistVisibilityToggle(
    isPublic: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clickable(enabled = enabled, onClick = onToggle),
    ) {
        Column(modifier = Modifier.width(224.dp)) {
            Text(
                text = stringResource(
                    if (isPublic) {
                        Res.string.playlists_create_public
                    } else {
                        Res.string.playlists_create_private
                    },
                ),
                color = DwijColors.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    if (isPublic) {
                        Res.string.playlists_create_public_description
                    } else {
                        Res.string.playlists_create_private_description
                    },
                ),
                color = DwijColors.CreateDialogSecondary,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            )
        }
        Box(
            modifier = Modifier
                .width(52.dp)
                .height(28.dp)
                .clip(CircleShape)
                .background(
                    if (isPublic) {
                        DwijColors.Pink.copy(alpha = 0.72f)
                    } else {
                        DwijColors.CreateDialogToggleBackground
                    },
                )
                .border(1.dp, DwijColors.CyanBright.copy(alpha = 0.65f), CircleShape),
        ) {
            Box(
                modifier = Modifier
                    .align(if (isPublic) Alignment.CenterEnd else Alignment.CenterStart)
                    .offset(x = if (isPublic) (-4).dp else 4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        if (isPublic) DwijColors.White else DwijColors.CreateDialogToggleKnob,
                    ),
            )
        }
    }
}

/** Рисует компактное действие диалога с отдельным состоянием загрузки. */
@Composable
private fun CreatePlaylistDialogButton(
    text: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    isLoading: Boolean = false,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(128.dp)
            .height(46.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(DwijColors.CreateDialogButtonBackground)
            .border(
                width = 1.dp,
                color = accent.copy(alpha = if (enabled || isLoading) 0.85f else 0.25f),
                shape = RoundedCornerShape(11.dp),
            )
            .clickable(enabled = enabled && !isLoading, onClick = onClick),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(21.dp),
            )
        } else {
            Text(
                text = text,
                color = DwijColors.White.copy(alpha = if (enabled) 1f else 0.38f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Показывает Яндекс-вариант диалога без route и сетевого запроса. */
@Preview(
    name = "Create Yandex playlist",
    widthDp = 390,
    heightDp = 720,
    showBackground = true,
    backgroundColor = DwijColors.BackgroundArgb,
)
@Composable
private fun CreatePlaylistDialogPreview() {
    CreatePlaylistDialog(
        source = HomeMusicSource.Yandex,
        isCreating = false,
        onDismiss = {},
        onCreate = { _, _ -> },
    )
}

private const val CREATE_PLAYLIST_TITLE_MAX_LENGTH = 80
