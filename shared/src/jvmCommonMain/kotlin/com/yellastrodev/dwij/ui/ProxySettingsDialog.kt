package com.yellastrodev.dwij.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yellastrodev.dwij.resources.Res
import com.yellastrodev.dwij.resources.settings_proxy_close
import com.yellastrodev.dwij.resources.settings_proxy_dialog_title
import com.yellastrodev.dwij.resources.settings_proxy_type_http
import com.yellastrodev.dwij.resources.settings_proxy_type_socks5
import com.yellastrodev.dwij.resources.settings_proxy_url_hint
import com.yellastrodev.dwij.resources.settings_proxy_url_hint_socks5
import com.yellastrodev.dwij.resources.settings_yandex_music
import com.yellastrodev.dwij.ui.theme.DwijColors
import com.yellastrodev.yamusicsdk.network.YamProxyType
import org.jetbrains.compose.resources.stringResource

/** Диалог прокси отдельных внешних сервисов. Пока поддерживает только Яндекс Музыку. */
@Composable
fun ProxySettingsDialog(
    proxyType: YamProxyType,
    proxyUrl: String,
    enabled: Boolean,
    isProxyUrlValid: Boolean,
    onProxyUrlChange: (String) -> Unit,
    onProxyUrlCommitted: () -> Unit,
    onProxyTypeChange: (YamProxyType) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusManager =
        LocalFocusManager.current

    var lastCommittedProxyUrl by remember {
        mutableStateOf(
            proxyUrl,
        )
    }

    fun commitIfChanged() {
        val normalized =
            proxyUrl.trim()

        if (
            normalized ==
            lastCommittedProxyUrl
        ) {
            return
        }

        lastCommittedProxyUrl =
            normalized

        onProxyUrlCommitted()
    }

    fun commitAndDismiss() {
        focusManager.clearFocus()
        commitIfChanged()
        onDismiss()
    }

    Dialog(
        onDismissRequest =
            ::commitAndDismiss,
        properties =
            DialogProperties(
                usePlatformDefaultWidth =
                    false,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 20.dp,
                    )
                    .widthIn(
                        max = 440.dp,
                    )
                    .clip(
                        RoundedCornerShape(20.dp),
                    )
                    .background(
                        DwijColors.CreateDialogBackground,
                    )
                    .border(
                        width = 1.dp,
                        color = DwijColors.CyanBright.copy(
                            alpha = 0.72f,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    )
                    .padding(
                        horizontal = 18.dp,
                        vertical = 16.dp,
                    ),
        ) {
            Text(
                text =
                    stringResource(
                        Res.string.settings_proxy_dialog_title,
                    ),
                color =
                    DwijColors.White,
                fontSize =
                    22.sp,
                fontWeight =
                    FontWeight.Bold,
            )

            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            top = 16.dp,
                        )
                        .clip(
                            RoundedCornerShape(14.dp),
                        )
                        .background(
                            DwijColors.SettingsCardBackground,
                        )
                        .border(
                            width = 1.dp,
                            color = DwijColors.Pink.copy(
                                alpha = 0.68f,
                            ),
                            shape = RoundedCornerShape(14.dp),
                        )
                        .padding(
                            horizontal = 14.dp,
                            vertical = 12.dp,
                        ),
            ) {
                Row(
                    verticalAlignment =
                        Alignment.CenterVertically,
                    modifier =
                        Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text =
                            stringResource(
                                Res.string.settings_yandex_music,
                            ),
                        color =
                            DwijColors.White,
                        fontSize =
                            16.sp,
                        fontWeight =
                            FontWeight.Bold,
                        modifier =
                            Modifier.weight(1f),
                    )

                    ProxyTypeSelector(
                        selectedType =
                            proxyType,
                        onTypeSelected = { type ->
                            focusManager.clearFocus()
                            commitIfChanged()
                            onProxyTypeChange(type)
                        },
                    )
                }

                Row(
                    verticalAlignment =
                        Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                top = 9.dp,
                            ),
                ) {
                    ProxyUrlField(
                        value =
                            proxyUrl,
                        hint =
                            stringResource(
                                when (proxyType) {
                                    YamProxyType.HTTP ->
                                        Res.string.settings_proxy_url_hint
                                    YamProxyType.SOCKS ->
                                        Res.string.settings_proxy_url_hint_socks5
                                },
                            ),
                        isValid =
                            isProxyUrlValid,
                        onValueChange =
                            onProxyUrlChange,
                        onCommitted =
                            ::commitIfChanged,
                        modifier =
                            Modifier.weight(1f),
                    )

                    Spacer(
                        modifier =
                            Modifier.width(12.dp),
                    )

                    Switch(
                        checked =
                            enabled,
                        onCheckedChange =
                            onEnabledChange,
                        enabled =
                            isProxyUrlValid,
                        colors =
                            SwitchDefaults.colors(
                                checkedThumbColor =
                                    DwijColors.White,
                                checkedTrackColor =
                                    DwijColors.Pink,
                                uncheckedThumbColor =
                                    DwijColors.SecondaryText,
                                uncheckedTrackColor =
                                    DwijColors.CreateDialogToggleBackground,
                            ),
                    )
                }
            }

            TextButton(
                onClick =
                    ::commitAndDismiss,
                modifier =
                    Modifier
                        .align(
                            Alignment.End,
                        )
                        .padding(
                            top = 8.dp,
                        ),
            ) {
                Text(
                    text =
                        stringResource(
                            Res.string.settings_proxy_close,
                        ),
                    color =
                        DwijColors.CyanBright,
                )
            }
        }
    }
}

/** Компактный переключатель HTTP/SOCKS5 в заголовке карточки Яндекс Музыки. */
@Composable
private fun ProxyTypeSelector(
    selectedType: YamProxyType,
    onTypeSelected: (YamProxyType) -> Unit,
) {
    var expanded by remember {
        mutableStateOf(false)
    }

    val selectedLabel =
        stringResource(
            when (selectedType) {
                YamProxyType.HTTP ->
                    Res.string.settings_proxy_type_http
                YamProxyType.SOCKS ->
                    Res.string.settings_proxy_type_socks5
            },
        )

    Box {
        Row(
            verticalAlignment =
                Alignment.CenterVertically,
            modifier =
                Modifier
                    .height(36.dp)
                    .clip(
                        RoundedCornerShape(9.dp),
                    )
                    .background(
                        DwijColors.CreateDialogFieldBackground,
                    )
                    .border(
                        width = 1.dp,
                        color = DwijColors.CyanBright.copy(
                            alpha = 0.58f,
                        ),
                        shape = RoundedCornerShape(9.dp),
                    )
                    .clickable(
                        role = Role.Button,
                        onClick = {
                            expanded = true
                        },
                    )
                    .padding(
                        horizontal = 10.dp,
                    ),
        ) {
            Text(
                text = selectedLabel,
                color = DwijColors.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )

            Spacer(
                modifier = Modifier.width(6.dp),
            )

            Text(
                text = "▾",
                color = DwijColors.CyanBright,
                fontSize = 12.sp,
            )
        }

        DropdownMenu(
            expanded =
                expanded,
            onDismissRequest = {
                expanded = false
            },
            modifier =
                Modifier.background(
                    DwijColors.CreateDialogBackground,
                ),
        ) {
            YamProxyType.entries.forEach { type ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text =
                                when (type) {
                                    YamProxyType.HTTP ->
                                        stringResource(
                                            Res.string.settings_proxy_type_http,
                                        )
                                    YamProxyType.SOCKS ->
                                        stringResource(
                                            Res.string.settings_proxy_type_socks5,
                                        )
                                },
                            color =
                                if (type == selectedType) {
                                    DwijColors.CyanBright
                                } else {
                                    DwijColors.White
                                },
                        )
                    },
                    onClick = {
                        expanded = false
                        if (type != selectedType) {
                            onTypeSelected(type)
                        }
                    },
                )
            }
        }
    }
}

/** Однострочное поле, которое коммитит значение только после реальной потери фокуса. */
@Composable
private fun ProxyUrlField(
    value: String,
    hint: String,
    isValid: Boolean,
    onValueChange: (String) -> Unit,
    onCommitted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager =
        LocalFocusManager.current

    var hadFocus by remember {
        mutableStateOf(false)
    }

    Box(
        contentAlignment =
            Alignment.CenterStart,
        modifier =
            modifier
                .height(48.dp)
                .clip(
                    RoundedCornerShape(10.dp),
                )
                .background(
                    DwijColors.CreateDialogFieldBackground,
                )
                .border(
                    width = 1.dp,
                    color =
                        if (
                            value.isBlank() ||
                            isValid
                        ) {
                            DwijColors.CyanBright.copy(
                                alpha = 0.58f,
                            )
                        } else {
                            DwijColors.Pink
                        },
                    shape =
                        RoundedCornerShape(10.dp),
                )
                .padding(
                    horizontal = 12.dp,
                ),
    ) {
        if (value.isEmpty()) {
            Text(
                text = hint,
                color =
                    DwijColors.CreateDialogHint,
                fontSize =
                    14.sp,
            )
        }

        BasicTextField(
            value =
                value,
            onValueChange = { newValue ->
                onValueChange(
                    newValue
                        .replace("\n", "")
                        .take(MAX_PROXY_URL_LENGTH),
                )
            },
            singleLine =
                true,
            textStyle =
                TextStyle(
                    color = DwijColors.White,
                    fontSize = 14.sp,
                ),
            cursorBrush =
                SolidColor(
                    DwijColors.Pink,
                ),
            keyboardOptions =
                KeyboardOptions(
                    imeAction = ImeAction.Done,
                    keyboardType = KeyboardType.Uri,
                ),
            keyboardActions =
                KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                    },
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            hadFocus = true
                        } else if (hadFocus) {
                            hadFocus = false
                            onCommitted()
                        }
                    },
        )
    }
}

private const val MAX_PROXY_URL_LENGTH =
    2_048
