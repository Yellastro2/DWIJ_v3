package com.yellastrodev.dwij.desktop.navigation

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import com.yellastrodev.dwij.desktop.DesktopPaths
import com.yellastrodev.dwij.ui.LocalYamLogger
import com.yellastrodev.vkmusicsdk.VkWebSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Управляет отдельным окном WebView2; busy/error приходят из общей проверки музыкальной сессии. */
@Composable
internal fun DesktopVkWebLoginBrowser(paths: DesktopPaths, onSession: (VkWebSession) -> Unit,
    onDismiss: () -> Unit, busy: Boolean, error: String?, openUrl: (String) -> Boolean) {
    val host = remember(paths) { DesktopVkWebView2Host(paths) }
    val logger = LocalYamLogger.current
    val currentSession = rememberUpdatedState(onSession)
    val currentDismiss = rememberUpdatedState(onDismiss)
    var hostStarted by remember { mutableStateOf(false) }
    var hostError by remember { mutableStateOf<String?>(null) }
    var runtimeMissing by remember { mutableStateOf(false) }

    DisposableEffect(host) { onDispose { host.close() } }
    LaunchedEffect(host) {
        try {
            withContext(Dispatchers.IO) { host.start() }
            hostStarted = true
            logger.info("VkWebView2", "[openVkWebLogin] Windows-компонент входа запущен")
            while (true) {
                when (val result = withContext(Dispatchers.IO) { host.readMessage() }) {
                    is VkWebView2Message.Session -> {
                        logger.info("VkWebView2", "[completeVkWebLogin] Получена браузерная сессия Windows; передаём на проверку")
                        currentSession.value(result.value)
                    }
                    is VkWebView2Message.Error -> {
                        runtimeMissing = result.code == "runtime_missing"
                        hostError = when (result.code) {
                            "runtime_missing" -> "Для входа нужен Microsoft Edge WebView2 Runtime. Установите его и откройте вход заново"
                            "already_open" -> "Окно входа VK уже открыто в другом экземпляре Движа"
                            else -> "Не удалось открыть браузер VK. Закройте окно и попробуйте снова"
                        }
                        logger.warning("VkWebView2", "[openVkWebLogin] Ошибка окна входа: причина=${if (runtimeMissing) "нет Runtime" else "не удалось открыть браузер"}")
                        break
                    }
                    VkWebView2Message.Closed -> { currentDismiss.value(); break }
                    null -> {
                        hostError = "Окно входа VK завершилось. Откройте вход заново"
                        break
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            hostError = "Не удалось запустить компонент входа VK. Проверьте desktop-сборку"
            logger.warning("VkWebView2", "[openVkWebLogin] Ошибка запуска или IPC: тип=${failure.javaClass.simpleName}")
        } finally { host.close() }
    }
    LaunchedEffect(host, hostStarted, busy, error) {
        if (hostStarted) withContext(Dispatchers.IO) { host.updateState(busy, error) }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Вход через сайт VK") },
        text = { Text(hostError ?: error ?: if (busy) "Подключаем аккаунт VK…"
            else "Вход открыт в отдельном окне VK. После входа аккаунт подключится автоматически") },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Закрыть") } },
        dismissButton = if (runtimeMissing) ({
            TextButton(onClick = { openUrl("https://developer.microsoft.com/microsoft-edge/webview2/#download-section") }) {
                Text("Скачать WebView2 Runtime")
            }
        }) else null,
    )
}
