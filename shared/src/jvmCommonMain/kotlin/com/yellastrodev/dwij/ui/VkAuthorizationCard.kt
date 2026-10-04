package com.yellastrodev.dwij.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.yellastrodev.dwij.resources.Res
import com.yellastrodev.dwij.resources.bg_party_texture
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
import com.yellastrodev.vkmusicsdk.VkWebSession
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Показывает аккаунт VK, Android-вход через сайт и сохранённые способы OAuth Маруси. */
@Composable
fun VkAuthorizationCard(repository: VkMusicRepository, platform: SettingsPlatform, onMessage: (String) -> Unit = {}) {
    val authorized by repository.authorized.collectAsState()
    val accountProfile by repository.accountProfile.collectAsState()
    val sessionRevision by repository.sessionRevision.collectAsState()
    val browserSession by repository.browserSession.collectAsState()
    LaunchedEffect(authorized, sessionRevision) {
        if (authorized) repository.loadAccountProfile()
    }
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
    var webBrowserOpen by rememberSaveable { mutableStateOf(false) }
    var manualLogin by rememberSaveable { mutableStateOf(true) }

    /** Сообщает о результате непосредственно в карточке и в snackbar экрана настроек. */
    fun reportMessage(text: String, isError: Boolean = true) {
        message = text
        messageIsError = isError
        onMessage(text)
    }

    /** Проверяет callback/state и сохраняет вход; в режиме только OAuth музыкальный API не вызывается. */
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
        logger.info("VkAuthorizationCard", "[completeVkAuthorization] Ссылка принята: автоматический=$automatic; state=${if (parsed.stateVerified) "совпадает" else "отсутствует, ручная вставка"}; только OAuth=${VkMusicRepository.AUTHORIZATION_ONLY}")
        scope.launch {
            try {
                when (val result = repository.authorize(parsed.accessToken)) {
                    is DataResult.Success -> {
                        logger.info("VkAuthorizationCard", "[completeVkAuthorization] Авторизация сохранена; только OAuth=${VkMusicRepository.AUTHORIZATION_ONLY}")
                        callback = ""
                        state = null
                        reportMessage(if (VkMusicRepository.AUTHORIZATION_ONLY) "Токен VK сохранён. Музыкальные запросы отключены"
                            else "Вход в ВК Музыку выполнен", isError = false)
                    }
                    is DataResult.Failure -> reportMessage(vkAuthorizationError(result.error))
                }
            } finally { busy = false }
        }
    }

    /** Начинает новую операцию; WebView и внешний браузер получают один и тот же OAuth-клиент. */
    fun beginLogin(embedded: Boolean) {
        webBrowserOpen = false
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

    /** Проверяет cookies в репозитории; при ошибке сохраняет окно сайта для повторного входа. */
    fun completeWebAuthorization(session: VkWebSession) {
        if (busy) return
        busy = true
        message = null
        messageIsError = false
        scope.launch {
            try {
                when (val result = repository.authorizeWebSession(session)) {
                    is DataResult.Success -> {
                        webBrowserOpen = false
                        reportMessage("Аккаунт VK подключён через сайт", isError = false)
                    }
                    is DataResult.Failure -> reportMessage(vkAuthorizationError(result.error))
                }
            } finally { busy = false }
        }
    }

    if (authorized) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            VkAuthorizedAccountCard(
                name = if (VkMusicRepository.AUTHORIZATION_ONLY && !browserSession) "Только OAuth: VK отключён"
                    else accountProfile?.displayName?.takeIf(String::isNotBlank) ?: "Авторизация сохранена",
                busy = busy,
                onLogout = {
                    busy = true
                    scope.launch {
                        try {
                            when (val result = repository.logout()) {
                                is DataResult.Success -> { state = null; callback = ""; message = null; messageIsError = false }
                                is DataResult.Failure -> reportMessage(vkAuthorizationError(result.error))
                            }
                        } finally { busy = false }
                    }
                },
            )
            message?.takeIf { messageIsError }?.let { Text(it, color = Color(0xFFFFB4AB)) }
        }
    } else Card(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = DwijColors.SettingsCardBackground, contentColor = DwijColors.White),
        border = BorderStroke(1.dp, Color(0xFF5B9BFF)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ВК Музыка", style = MaterialTheme.typography.titleLarge)
            Text("Войдите, чтобы искать и слушать треки VK")
            message?.let { Text(it, color = if (messageIsError) Color(0xFFFFB4AB) else DwijColors.CyanBright) }
            if (busy) Text(if (webBrowserOpen) "Подключаем аккаунт VK…"
                else if (VkMusicRepository.AUTHORIZATION_ONLY) "Сохраняем авторизацию VK…"
                else "Проверяем доступ к VK Music…", color = DwijColors.CyanBright)
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
            if (platform.hasVkWebLogin) {
                OutlinedButton(
                    enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DwijColors.White),
                    border = BorderStroke(1.dp, Color(0xFF5B9BFF)),
                    onClick = {
                        state = null
                        callback = ""
                        browserOpen = false
                        message = null
                        messageIsError = false
                        webBrowserOpen = true
                    },
                ) { Text("Войти через сайт VK") }
            }
            OutlinedButton(
                enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = DwijColors.White, disabledContentColor = Color(0xFFCBD5E1),
                ),
                border = BorderStroke(1.dp, Color(0xFF5B9BFF)),
                onClick = { beginLogin(platform.hasEmbeddedVkLogin) },
            ) { Text(if (state != null) "Открыть вход заново" else "Войти в VK через Марусю") }
            if (platform.hasEmbeddedVkLogin) {
                TextButton(enabled = !busy, onClick = { beginLogin(embedded = false) }) {
                    Text("Войти через внешний браузер", color = DwijColors.CyanBright)
                }
            }
            if (platform.canResetVkBrowserSession) {
                TextButton(enabled = !busy && !browserOpen && !webBrowserOpen, onClick = {
                    busy = true
                    state = null
                    callback = ""
                    scope.launch {
                        try {
                            platform.resetVkBrowserSession()
                            reportMessage("Сессия браузера сброшена. Откройте вход VK заново", isError = false)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            logger.warning("VkAuthorizationCard", "[resetVkBrowserSession] Ошибка очистки: тип=${error.javaClass.simpleName}")
                            reportMessage("Не удалось сбросить сессию браузера")
                        } finally { busy = false }
                    }
                }) { Text("Сбросить сессию браузера VK", color = DwijColors.CyanBright) }
            }
        }
    }
    if (webBrowserOpen) {
        platform.VkWebLoginBrowser(onSession = ::completeWebAuthorization,
            onDismiss = { webBrowserOpen = false; message = null; messageIsError = false },
            busy = busy, error = message?.takeIf { messageIsError })
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

/** Повторяет раскладку карточки ЯМ: знак источника, название, имя аккаунта и удаление справа. */
@Composable
private fun VkAuthorizedAccountCard(name: String, busy: Boolean, onLogout: () -> Unit) {
    val accent = Color(0xFF5B9BFF)
    SettingsTextureCard(Res.drawable.bg_party_texture, accent, Modifier.height(142.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Box(contentAlignment = Alignment.Center,
                modifier = Modifier.size(62.dp).background(Color(0xFF0077FF), RoundedCornerShape(14.dp))) {
                Text("VK", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text("ВК Музыка", color = DwijColors.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(name, color = DwijColors.CyanBright, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
            }
            SettingsActionButton("Удалить", enabled = !busy, isLoading = busy, accent = accent, onClick = onLogout)
        }
    }
}

/** Безопасное сообщение о проверке VK без токена или содержимого OAuth callback. */
private fun vkAuthorizationError(error: DataError): String = when (error) {
    DataError.Unauthorized -> "VK отклонил токен. Выполните вход заново"
    is DataError.Remote -> "VK Music вернул ошибку ${error.code}. Вход не сохранён"
    else -> "Не удалось проверить или сохранить доступ VK. Повторите попытку"
}
