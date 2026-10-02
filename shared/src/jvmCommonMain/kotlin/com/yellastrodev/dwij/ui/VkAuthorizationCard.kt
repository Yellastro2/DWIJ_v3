package com.yellastrodev.dwij.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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

/** VK-вход: Android-WebView с автоматическим callback либо ручной браузер; токен не сохраняется в saved state. */
@Composable
fun VkAuthorizationCard(repository: VkMusicRepository, platform: SettingsPlatform, onMessage: (String) -> Unit = {}) {
    val authorized by repository.authorized.collectAsState()
    val scope = rememberCoroutineScope()
    val logger = LocalYamLogger.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var state by rememberSaveable { mutableStateOf<String?>(null) }
    // Callback содержит токен: не сохраняем его в saved state или обычные настройки.
    var callback by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var browserOpen by rememberSaveable { mutableStateOf(false) }
    var manualLogin by rememberSaveable { mutableStateOf(true) }

    /** Сообщает о результате непосредственно в карточке и в snackbar экрана настроек. */
    fun reportMessage(text: String, isError: Boolean = true) {
        message = text
        messageIsError = isError
        onMessage(text)
    }

    /** Проверяет callback, затем музыкальный доступ; автоматический вход требует совпадающий state. */
    fun completeAuthorization(value: String, automatic: Boolean) {
        if (busy) return
        browserOpen = false
        focusManager.clearFocus()
        keyboardController?.hide()
        val parsed = try {
            if (automatic) VkOAuth.parseAutomaticRedirect(value, state.orEmpty())
            else VkOAuth.parseManualRedirect(value, state.orEmpty())
        } catch (error: VkRedirectException) {
            reportMessage(error.reason.description)
            logger.warning("VkAuthorizationCard", "[completeVkAuthorization] Ссылка отклонена: причина=${error.reason.name}; автоматический=$automatic")
            return
        } catch (error: Exception) {
            reportMessage("Не удалось разобрать адрес. Скопируйте полный URL из браузера заново")
            logger.warning("VkAuthorizationCard", "[completeVkAuthorization] Ошибка разбора ссылки: тип=${error.javaClass.simpleName}")
            return
        }
        busy = true
        message = null
        messageIsError = false
        logger.info("VkAuthorizationCard", "[completeVkAuthorization] Ссылка принята: автоматический=$automatic; state=${if (parsed.stateVerified) "совпадает" else "отсутствует, ручная вставка"}; проверяем VK Music")
        scope.launch {
            try {
                when (val result = repository.authorize(parsed.accessToken)) {
                    is DataResult.Success -> {
                        logger.info("VkAuthorizationCard", "[completeVkAuthorization] Доступ к VK Music подтверждён, авторизация сохранена")
                        callback = ""
                        state = null
                        reportMessage("Вход в ВК Музыку выполнен", isError = false)
                    }
                    is DataResult.Failure -> reportMessage(vkAuthorizationError(result.error))
                }
            } finally { busy = false }
        }
    }

    /** Начинает новую операцию; WebView и внешний браузер получают один и тот же OAuth-клиент. */
    fun beginLogin(embedded: Boolean) {
        state = VkOAuth.newState()
        callback = ""
        message = null
        messageIsError = false
        manualLogin = !embedded
        browserOpen = embedded
        if (!embedded && !platform.openUrl(VkOAuth.authorizationUrl(state.orEmpty()))) {
            reportMessage("Не удалось открыть браузер")
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = DwijColors.SettingsCardBackground, contentColor = DwijColors.White),
        border = BorderStroke(1.dp, Color(0xFF5B9BFF)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ВК Музыка", style = MaterialTheme.typography.titleLarge)
            Text(if (authorized) "Авторизация сохранена" else "Войдите, чтобы искать и слушать треки VK")
            message?.let { Text(it, color = if (messageIsError) Color(0xFFFFB4AB) else DwijColors.CyanBright) }
            if (busy) Text("Проверяем доступ к VK Music…", color = DwijColors.CyanBright)
            if (state != null && manualLogin) {
                Text("После входа скопируйте полный адрес страницы из адресной строки браузера и вставьте сюда.")
                OutlinedTextField(
                    value = callback, onValueChange = { callback = it; message = null; messageIsError = false }, enabled = !busy,
                    isError = messageIsError,
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
                        errorTextColor = DwijColors.White, errorLabelColor = Color(0xFFFFB4AB),
                        errorBorderColor = Color(0xFFFFB4AB), errorCursorColor = DwijColors.CyanBright,
                        errorContainerColor = DwijColors.SettingsCardBackground,
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
                    onClick = { completeAuthorization(callback, automatic = false) },
                ) { Text(if (busy) "Проверяем доступ…" else "Завершить вход") }
            }
            OutlinedButton(
                enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = DwijColors.White, disabledContentColor = Color(0xFFCBD5E1),
                ),
                border = BorderStroke(1.dp, Color(0xFF5B9BFF)),
                onClick = { beginLogin(platform.hasEmbeddedVkLogin) },
            ) { Text(if (state != null) "Открыть вход заново" else if (authorized) "Сменить аккаунт VK" else "Войти в VK через Марусю") }
            if (platform.hasEmbeddedVkLogin) {
                TextButton(enabled = !busy, onClick = { beginLogin(embedded = false) }) {
                    Text("Войти через внешний браузер", color = DwijColors.CyanBright)
                }
            }
            if (authorized) {
                TextButton(enabled = !busy, colors = ButtonDefaults.textButtonColors(
                    contentColor = DwijColors.CyanBright, disabledContentColor = Color(0xFFCBD5E1),
                ), onClick = {
                    busy = true
                    scope.launch {
                        try {
                            when (val result = repository.logout()) {
                                is DataResult.Success -> { state = null; callback = ""; message = null; messageIsError = false }
                                is DataResult.Failure -> reportMessage(vkAuthorizationError(result.error))
                            }
                        } finally { busy = false }
                    }
                }) { Text("Удалить авторизацию VK") }
            }
        }
    }
    if (browserOpen && state != null) {
        platform.VkLoginBrowser(VkOAuth.authorizationUrl(state.orEmpty()),
            onRedirect = { completeAuthorization(it, automatic = true) },
            onDismiss = { browserOpen = false; state = null; callback = "" },
            onOpenExternalBrowser = {
                browserOpen = false
                manualLogin = true
                if (!platform.openUrl(VkOAuth.authorizationUrl(state.orEmpty()))) reportMessage("Не удалось открыть браузер")
            })
    }
}

/** Безопасное сообщение о проверке VK без токена или содержимого OAuth callback. */
private fun vkAuthorizationError(error: DataError): String = when (error) {
    DataError.Unauthorized -> "VK отклонил токен. Выполните вход заново"
    is DataError.Remote -> "VK Music вернул ошибку ${error.code}. Вход не сохранён"
    else -> "Не удалось проверить или сохранить доступ VK. Повторите попытку"
}
