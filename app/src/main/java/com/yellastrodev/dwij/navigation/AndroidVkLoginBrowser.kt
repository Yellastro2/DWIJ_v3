package com.yellastrodev.dwij.navigation

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yellastrodev.dwij.ui.theme.DwijColors
import com.yellastrodev.vkmusicsdk.VkOAuth
import kotlinx.coroutines.delay

/** Android OAuth с desktop UA, автоматическим callback и заметным состоянием ожидания загрузки страницы. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun AndroidVkLoginBrowser(url: String, onRedirect: (String) -> Unit,
    onDismiss: () -> Unit, onOpenExternalBrowser: () -> Unit) {
    val context = LocalContext.current
    val currentRedirect = rememberUpdatedState(onRedirect)
    var loading by remember(url) { mutableStateOf(true) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    var completed by remember(url) { mutableStateOf(false) }
    var longWait by remember(url) { mutableStateOf(false) }
    var externalMessage by remember(url) { mutableStateOf<String?>(null) }

    LaunchedEffect(loading) {
        longWait = false
        if (loading) {
            delay(8_000)
            longWait = true
        }
    }

    /** Перехватывает callback один раз до загрузки страницы; в логах только тип ответа, без URL/профиля/токена. */
    fun consumeRedirect(candidate: String?): Boolean {
        if (candidate == null || !VkOAuth.isRedirectUrl(candidate)) return false
        if (!completed) {
            completed = true
            val fragment = Uri.parse(candidate).encodedFragment.orEmpty()
            val kind = when {
                fragment.startsWith("access_token=") || fragment.contains("&access_token=") -> "access_token"
                fragment.startsWith("payload=") || fragment.contains("&payload=") -> "payload VK ID"
                else -> "без токена"
            }
            Log.i(TAG, "[consumeVkRedirect] Получен callback: тип=$kind")
            currentRedirect.value(candidate)
        }
        return true
    }

    val browser = remember(url, context) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = vkDesktopUserAgent(settings.userAgentString)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                /** Не передаёт console-вывод сторонней страницы в логи приложения. */
                override fun onConsoleMessage(message: ConsoleMessage?): Boolean = true
            }
            webViewClient = object : WebViewClient() {
                /** Callback остаётся в приложении; intent/VK-ссылки открывают внешнее приложение или HTTPS fallback. */
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    if (request == null || !request.isForMainFrame) return false
                    val target = request.url.toString()
                    if (consumeRedirect(target)) return true
                    if (request.url.scheme != "https") {
                        loading = false
                        val sourceHost = view?.url?.let { Uri.parse(it).host }
                        if (!isVkLoginHost(sourceHost)) {
                            error = "Не удалось открыть внешнее приложение с этой страницы. Используйте вход через браузер."
                            Log.w(TAG, "[navigateVkLogin] Внешний переход отклонён: источник вне VK, схема=${request.url.scheme}")
                            return true
                        }
                        when (val navigation = openVkExternalLink(context, target)) {
                            is VkExternalNavigation.Opened -> {
                                error = null
                                externalMessage = "Открыто внешнее приложение. После входа вернитесь сюда. Если вход завершился в браузере, используйте «Открыть в браузере» и вставьте итоговую ссылку."
                            }
                            is VkExternalNavigation.Fallback -> {
                                error = null
                                externalMessage = null
                                loading = true
                                if (!consumeRedirect(navigation.url)) view?.loadUrl(navigation.url)
                            }
                            is VkExternalNavigation.Unavailable -> {
                                error = "Не удалось открыть приложение для входа VK. Попробуйте вход через браузер."
                            }
                        }
                        return true
                    }
                    return false
                }

                /** Старт основного документа также ловит редиректы; диагностика содержит только hostname. */
                override fun onPageStarted(view: WebView?, target: String?, favicon: Bitmap?) {
                    if (consumeRedirect(target)) { view?.stopLoading(); return }
                    if (completed) return
                    loading = true
                    error = null
                    externalMessage = null
                    Log.d(TAG, "[loadVkLoginPage] Загрузка страницы: host=${target?.let { Uri.parse(it).host }.orEmpty()}")
                }

                /** Завершение ловит callback при изменении hash; полная ссылка никуда не сохраняется. */
                override fun onPageFinished(view: WebView?, target: String?) {
                    if (!consumeRedirect(target) && !completed) loading = false
                }

                /** Ловит изменения URL средствами JS без обязательной загрузки нового документа. */
                override fun doUpdateVisitedHistory(view: WebView?, target: String?, isReload: Boolean) {
                    consumeRedirect(target)
                }

                /** Ошибки основного документа показываются без описания WebView, которое может содержать URL. */
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, failure: WebResourceError?) {
                    if (request?.isForMainFrame != true || completed) return
                    loading = false
                    error = "Не удалось загрузить страницу VK. Повторите попытку или откройте вход в браузере."
                    Log.w(TAG, "[loadVkLoginPage] Сетевая ошибка: код=${failure?.errorCode}")
                }

                /** Ошибка сертификата отменяет запрос; пользователь может повторить вход через браузер. */
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, failure: SslError?) {
                    handler?.cancel()
                    if (completed) return
                    loading = false
                    error = "Не удалось установить защищённое соединение с VK."
                    Log.w(TAG, "[loadVkLoginPage] Ошибка TLS: код=${failure?.primaryError}")
                }
            }
        }
    }

    DisposableEffect(browser) {
        Log.i(TAG, "[openVkLogin] Открываем встроенный браузер с desktop User-Agent")
        browser.loadUrl(url)
        onDispose {
            completed = true
            browser.stopLoading()
            browser.webViewClient = WebViewClient()
            browser.webChromeClient = null
            (browser.parent as? ViewGroup)?.removeView(browser)
            browser.removeAllViews()
            browser.destroy()
            Log.d(TAG, "[closeVkLogin] Встроенный браузер закрыт")
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) {
        Column(Modifier.fillMaxSize().background(DwijColors.Background).systemBarsPadding().imePadding()) {
            Text("Вход в ВК Музыку", color = DwijColors.White, modifier = Modifier.padding(16.dp, 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onDismiss) { Text("Закрыть", color = DwijColors.White) }
                TextButton(onClick = onOpenExternalBrowser) { Text("Открыть в браузере", color = DwijColors.CyanBright) }
            }
            error?.let { message ->
                Text(message, color = DwijColors.White, modifier = Modifier.padding(horizontal = 16.dp))
                TextButton(onClick = { error = null; loading = true; browser.loadUrl(url) }) {
                    Text("Повторить", color = DwijColors.CyanBright)
                }
            }
            externalMessage?.let { message ->
                Text(message, color = DwijColors.White, modifier = Modifier.padding(16.dp, 8.dp))
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = DwijColors.CyanBright)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                AndroidView(factory = { browser }, modifier = Modifier.fillMaxSize())
                if (loading && error == null) {
                    Column(
                        modifier = Modifier.align(Alignment.Center)
                            .padding(24.dp).background(DwijColors.Background, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(color = DwijColors.CyanBright)
                        Text("Загружаем страницу входа VK…", color = DwijColors.White, textAlign = TextAlign.Center)
                        if (longWait) {
                            Text("VK отвечает дольше обычного. Можно подождать или открыть вход в браузере.",
                                color = DwijColors.White, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

/** Подменяет платформу на Windows, сохраняя фактическую версию Chromium установленного WebView. */
internal fun vkDesktopUserAgent(original: String): String {
    val chromeVersion = Regex("Chrome/([0-9.]+)").find(original)?.groupValues?.get(1)
        ?: return original.replace("; wv", "").replace(" Mobile", "")
    return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
}

private const val TAG = "VkLoginWebView"

/** Результат запуска внешнего входа: окно WebView не закрывается и URL не сериализуется. */
private sealed interface VkExternalNavigation {
    data object Opened : VkExternalNavigation
    class Fallback(val url: String) : VkExternalNavigation
    data object Unavailable : VkExternalNavigation
}

/** Проверяет домен основной страницы перед запуском внешнего перехода из VK-входа. */
private fun isVkLoginHost(host: String?): Boolean = host?.lowercase()?.let {
    it == "vk.ru" || it.endsWith(".vk.ru") || it == "vk.com" || it.endsWith(".vk.com")
} == true

/** Разбирает browser intent/VK-схему, удаляет явные компоненты и grant-флаги; при отсутствии приложения использует VK HTTPS fallback. */
private fun openVkExternalLink(context: Context, value: String): VkExternalNavigation {
    val uri = Uri.parse(value)
    val navigation = try {
        if (uri.scheme.equals("intent", true)) Intent.parseUri(value, Intent.URI_INTENT_SCHEME)
        else Intent(Intent.ACTION_VIEW, uri)
    } catch (_: Exception) {
        Log.w(TAG, "[openVkExternalLink] Не удалось разобрать внешний переход")
        return VkExternalNavigation.Unavailable
    }
    val fallback = navigation.getStringExtra("browser_fallback_url")?.takeIf {
        val address = Uri.parse(it)
        address.scheme == "https" && isVkLoginHost(address.host) && address.userInfo == null && address.port in setOf(-1, 443)
    }
    val destination = navigation.data
    val scheme = destination?.scheme?.lowercase()
    val allowed = scheme?.matches(Regex("vk[a-z0-9_-]*")) == true ||
        (scheme == "https" && isVkLoginHost(destination?.host))
    if (!allowed) {
        Log.w(TAG, "[openVkExternalLink] Неподдерживаемая схема: $scheme; fallback=${fallback != null}")
        return fallback?.let { VkExternalNavigation.Fallback(it) } ?: VkExternalNavigation.Unavailable
    }
    navigation.component = null
    navigation.selector = null
    navigation.action = Intent.ACTION_VIEW
    navigation.addCategory(Intent.CATEGORY_BROWSABLE)
    navigation.flags = Intent.FLAG_ACTIVITY_NEW_TASK
    navigation.removeExtra("browser_fallback_url")
    return try {
        context.startActivity(navigation)
        Log.i(TAG, "[openVkExternalLink] Внешнее приложение открыто: схема=$scheme; пакет=${navigation.`package`.orEmpty()}; fallback=${fallback != null}")
        VkExternalNavigation.Opened
    } catch (_: ActivityNotFoundException) {
        Log.w(TAG, "[openVkExternalLink] Приложение не найдено: схема=$scheme; fallback=${fallback != null}")
        fallback?.let { VkExternalNavigation.Fallback(it) } ?: VkExternalNavigation.Unavailable
    } catch (_: SecurityException) {
        Log.w(TAG, "[openVkExternalLink] Android отклонил внешний переход: схема=$scheme; fallback=${fallback != null}")
        fallback?.let { VkExternalNavigation.Fallback(it) } ?: VkExternalNavigation.Unavailable
    }
}
