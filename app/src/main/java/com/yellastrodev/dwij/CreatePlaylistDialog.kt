package com.yellastrodev.dwij

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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

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
                .background(CreateDialogBackground)
                .border(
                    width = 1.dp,
                    color = CreateDialogPink.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(20.dp),
                ),
        ) {
            Image(
                painter = painterResource(R.drawable.bg_focus_texture),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.22f,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.38f))
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text(
                    text = stringResource(R.string.playlists_create_dialog_title),
                    color = Color.White,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(
                        if (source == HomeMusicSource.Yandex) {
                            R.string.home_source_yandex_music
                        } else {
                            R.string.home_source_local
                        },
                    ),
                    color = CreateDialogCyan,
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
                        text = stringResource(R.string.playlists_cancel),
                        accent = CreateDialogCyan,
                        enabled = !isCreating,
                        onClick = onDismiss,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    CreatePlaylistDialogButton(
                        text = stringResource(R.string.playlists_create_confirm),
                        accent = CreateDialogPink,
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
            text = stringResource(R.string.playlists_create_name_label),
            color = Color(0xFFD7DAE7),
            fontSize = 12.sp,
        )
        Box(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .height(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xD9070A14))
                .border(
                    width = 1.dp,
                    color = if (enabled) CreateDialogCyan.copy(alpha = 0.72f) else Color.DarkGray,
                    shape = RoundedCornerShape(10.dp),
                )
                .padding(horizontal = 13.dp),
        ) {
            if (value.isEmpty()) {
                Text(
                    text = stringResource(R.string.playlists_create_name_hint),
                    color = Color(0xFF73798B),
                    fontSize = 15.sp,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 16.sp,
                ),
                cursorBrush = SolidColor(CreateDialogPink),
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
                        R.string.playlists_create_public
                    } else {
                        R.string.playlists_create_private
                    },
                ),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    if (isPublic) {
                        R.string.playlists_create_public_description
                    } else {
                        R.string.playlists_create_private_description
                    },
                ),
                color = Color(0xFF9297A8),
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
                        CreateDialogPink.copy(alpha = 0.72f)
                    } else {
                        Color(0xFF242938)
                    },
                )
                .border(1.dp, CreateDialogCyan.copy(alpha = 0.65f), CircleShape),
        ) {
            Box(
                modifier = Modifier
                    .align(if (isPublic) Alignment.CenterEnd else Alignment.CenterStart)
                    .offset(x = if (isPublic) (-4).dp else 4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (isPublic) Color.White else Color(0xFF8F96A9)),
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
            .background(Color(0xD90A0D18))
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
                color = Color.White.copy(alpha = if (enabled) 1f else 0.38f),
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
    backgroundColor = 0xFF03040F,
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
private val CreateDialogBackground = Color(0xFF050711)
private val CreateDialogPink = Color(0xFFFF00BF)
private val CreateDialogCyan = Color(0xFF00DFFF)
