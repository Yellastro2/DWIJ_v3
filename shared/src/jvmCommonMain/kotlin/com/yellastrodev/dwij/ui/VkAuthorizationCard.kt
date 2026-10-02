package com.yellastrodev.dwij.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import com.yellastrodev.dwij.ui.theme.DwijColors
import androidx.compose.ui.unit.dp
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.repo.VkMusicRepository
import com.yellastrodev.dwij.navigation.SettingsPlatform
import com.yellastrodev.vkmusicsdk.VkOAuth
import com.yellastrodev.vkmusicsdk.VkRedirectException
import kotlinx.coroutines.launch

/** Браузерный вход VK с читаемым многострочным callback, явными цветами и безопасной диагностикой. */
@Composable
fun VkAuthorizationCard(repository: VkMusicRepository, platform: SettingsPlatform) {
    val authorized by repository.authorized.collectAsState()
    val scope = rememberCoroutineScope()
    val logger = LocalYamLogger.current
    var state by rememberSaveable { mutableStateOf<String?>(null) }
    // Callback содержит токен: не сохраняем его в saved state или обычные настройки.
    var callback by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = DwijColors.SettingsCardBackground, contentColor = DwijColors.White),
        border = BorderStroke(1.dp, Color(0xFF5B9BFF)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ВК Музыка", style = MaterialTheme.typography.titleLarge)
            Text(if (authorized) "Авторизация сохранена" else "Войдите, чтобы искать и слушать треки VK")
            if (state != null) {
                Text("После входа скопируйте полный адрес страницы из адресной строки браузера и вставьте сюда.")
                OutlinedTextField(
                    value = callback, onValueChange = { callback = it; message = null }, enabled = !busy,
                    label = { Text("Адрес страницы после входа") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = DwijColors.White, unfocusedTextColor = DwijColors.White,
                        disabledTextColor = DwijColors.White.copy(alpha = 0.75f),
                        focusedLabelColor = DwijColors.CyanBright, unfocusedLabelColor = DwijColors.White,
                        disabledLabelColor = DwijColors.White.copy(alpha = 0.75f),
                        unfocusedBorderColor = Color(0xFF5B9BFF),
                        disabledBorderColor = Color(0xFF5B9BFF).copy(alpha = 0.65f),
                        focusedContainerColor = DwijColors.SettingsCardBackground,
                        unfocusedContainerColor = DwijColors.SettingsCardBackground,
                        disabledContainerColor = DwijColors.SettingsCardBackground,
                        focusedBorderColor = DwijColors.CyanBright, cursorColor = DwijColors.CyanBright,
                    ),
                    minLines = 3, maxLines = 6,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp),
                )
                Button(
                    enabled = !busy && callback.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DwijColors.CyanBright, contentColor = DwijColors.Black,
                        disabledContainerColor = Color(0xFF263348), disabledContentColor = Color(0xFFCBD5E1),
                    ),
                    onClick = {
                        val parsed = try {
                            VkOAuth.parseManualRedirect(callback, state.orEmpty())
                        } catch (error: VkRedirectException) {
                            message = error.reason.description
                            logger.warning("VkAuthorizationCard", "[completeVkAuthorization] Ссылка отклонена: причина=${error.reason.name}; ${error.reason.description}")
                            return@Button
                        } catch (error: Exception) {
                            message = "Не удалось разобрать адрес. Скопируйте полный URL из браузера заново"
                            logger.warning("VkAuthorizationCard", "[completeVkAuthorization] Ошибка разбора ссылки: тип=${error.javaClass.simpleName}")
                            return@Button
                        }
                        busy = true
                        message = null
                        logger.info("VkAuthorizationCard", "[completeVkAuthorization] Ссылка принята: state=${if (parsed.stateVerified) "совпадает" else "отсутствует, ручная вставка"}; проверяем доступ к VK Music")
                        scope.launch {
                            try {
                                when (val result = repository.authorize(parsed.accessToken)) {
                                    is DataResult.Success -> {
                                        logger.info("VkAuthorizationCard", "[completeVkAuthorization] Доступ к VK Music подтверждён, авторизация сохранена")
                                        callback = ""
                                        state = null
                                        message = "Вход в ВК Музыку выполнен"
                                    }
                                    is DataResult.Failure -> message = vkAuthorizationError(result.error)
                                }
                            } finally { busy = false }
                        }
                    },
                ) { Text(if (busy) "Проверяем доступ…" else "Завершить вход") }
            }
            OutlinedButton(
                enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = DwijColors.White, disabledContentColor = Color(0xFFCBD5E1),
                ),
                border = BorderStroke(1.dp, Color(0xFF5B9BFF)),
                onClick = {
                    val nextState = VkOAuth.newState()
                    if (platform.openUrl(VkOAuth.authorizationUrl(nextState))) {
                        state = nextState
                        callback = ""
                        message = null
                    } else message = "Не удалось открыть браузер"
                },
            ) { Text(if (state != null) "Открыть вход заново" else if (authorized) "Сменить аккаунт VK" else "Войти в VK через Марусю") }
            if (authorized) {
                TextButton(enabled = !busy, colors = ButtonDefaults.textButtonColors(
                    contentColor = DwijColors.CyanBright, disabledContentColor = Color(0xFFCBD5E1),
                ), onClick = {
                    busy = true
                    scope.launch {
                        try {
                            when (val result = repository.logout()) {
                                is DataResult.Success -> { state = null; callback = ""; message = null }
                                is DataResult.Failure -> message = vkAuthorizationError(result.error)
                            }
                        } finally { busy = false }
                    }
                }) { Text("Удалить авторизацию VK") }
            }
            message?.let { Text(it) }
        }
    }
}

/** Безопасное сообщение о проверке VK без токена или содержимого OAuth callback. */
private fun vkAuthorizationError(error: DataError): String = when (error) {
    DataError.Unauthorized -> "VK отклонил токен. Выполните вход заново"
    is DataError.Remote -> "VK Music вернул ошибку ${error.code}. Вход не сохранён"
    else -> "Не удалось проверить или сохранить доступ VK. Повторите попытку"
}
