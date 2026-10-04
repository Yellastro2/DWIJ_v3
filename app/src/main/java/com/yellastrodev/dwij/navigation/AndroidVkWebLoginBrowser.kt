package com.yellastrodev.dwij.navigation

import android.annotation.SuppressLint
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yellastrodev.dwij.ui.theme.DwijColors
import com.yellastrodev.vkmusicsdk.VkWebSession

/** Обычный веб-вход без OAuth: нативный CookieManager передаёт p/remixsid только после страницы VK. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun AndroidVkWebLoginBrowser(onSession: (VkWebSession) -> Unit, onDismiss: () -> Unit,
    busy: Boolean, error: String?) {
    val context = LocalContext.current
    val currentSession = rememberUpdatedState(onSession)
    val currentBusy = rememberUpdatedState(busy)
    var loading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }
    // Секреты остаются только в памяти, включая защиту от повторов onPageFinished/history.
    var attemptedCookies by remember { mutableStateOf<Pair<String, String>?>(null) }
    var submitted by remember { mutableStateOf(false) }

    /** Передаёт одну новую пару cookies либо явно повторяет проверку по кнопке пользователя. */
    fun completeLogin(view: WebView, manual: Boolean = false) {
        if (currentBusy.value || (!manual && submitted)) return
        if (!isVkWebUrl(view.url)) return
        val session = readVkWebSession(view.settings.userAgentString, view.url)
        if (session == null) {
            if (manual) pageError = "Сначала войдите в аккаунт на сайте VK"
            return
        }
        val pair = session.p to session.remixsid
        if (!manual && attemptedCookies == pair) return
        attemptedCookies = pair
        submitted = true
        pageError = null
        CookieManager.getInstance().flush()
        Log.i(WEB_TAG, "[completeVkWebLogin] Браузерная сессия получена; начинаем проверку без вывода cookies")
        currentSession.value(session)
    }

    val browser = remember(context) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Используем фактический Android UA этого WebView и тот же UA при web_token/API.
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                /** Не записывает сообщения стороннего сайта, которые могут содержать секреты. */
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
            }
            webViewClient = object : WebViewClient() {
                /** Вход остаётся на HTTPS-доменах VK, без открытия приложения VK или внешнего браузера. */
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    if (request?.isForMainFrame != true) return false
                    if (isVkWebUrl(request.url.toString())) return false
                    pageError = "Продолжите вход на сайте VK в этом окне"
                    return true
                }

                /** Отмечает начало загрузки без журналирования URL. */
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    loading = true
                    pageError = null
                }

                /** После завершения страницы проверяет наличие cookies без JavaScript-моста. */
                override fun onPageFinished(view: WebView?, url: String?) {
                    loading = false
                    if (view != null && isVkWebLanding(url)) completeLogin(view)
                }

                /** Ловит переход на страницу аккаунта при навигации сайта без перезагрузки документа. */
                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    if (view != null && isVkWebLanding(url)) completeLogin(view)
                }

                /** Показывает безопасную ошибку основного документа без приватных параметров URL. */
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, failure: WebResourceError?) {
                    if (request?.isForMainFrame != true) return
                    loading = false
                    pageError = "Не удалось загрузить сайт VK. Проверьте соединение"
                    Log.w(WEB_TAG, "[loadVkWebLogin] Ошибка загрузки: код=${failure?.errorCode}")
                }

                /** Не продолжает вход при недействительном TLS-сертификате. */
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, failure: SslError?) {
                    handler?.cancel()
                    loading = false
                    pageError = "Не удалось проверить защищённое соединение с VK"
                    Log.w(WEB_TAG, "[loadVkWebLogin] Ошибка TLS: код=${failure?.primaryError}")
                }
            }
        }
    }

    LaunchedEffect(busy, error) {
        if (!busy && error != null) submitted = false
    }
    DisposableEffect(browser) {
        Log.i(WEB_TAG, "[openVkWebLogin] Открываем обычный сайт VK с Android User-Agent")
        browser.loadUrl("https://vk.ru/")
        onDispose {
            browser.stopLoading()
            browser.webViewClient = WebViewClient()
            browser.webChromeClient = null
            (browser.parent as? ViewGroup)?.removeView(browser)
            browser.removeAllViews()
            browser.destroy()
        }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = true,
    )) {
        Column(Modifier.fillMaxSize().background(DwijColors.Background)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (loading || busy) CircularProgressIndicator(Modifier.size(20.dp), color = DwijColors.CyanBright, strokeWidth = 2.dp)
                Spacer(Modifier.weight(1f))
                TextButton(enabled = !busy, onClick = { completeLogin(browser, manual = true) }) {
                    Text("Я вошёл", color = DwijColors.CyanBright)
                }
                TextButton(enabled = !busy, onClick = onDismiss) { Text("Закрыть", color = DwijColors.CyanBright) }
            }
            if (busy) Text("Подключаем аккаунт VK…", color = DwijColors.CyanBright, modifier = Modifier.padding(8.dp))
            (error ?: pageError)?.let { Text(it, color = DwijColors.White, modifier = Modifier.padding(8.dp)) }
            AndroidView(factory = { browser }, modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }
}

/** Разрешает только HTTPS и поддомены VK без userInfo и нестандартного порта. */
private fun isVkWebUrl(value: String?): Boolean {
    val uri = value?.let(Uri::parse) ?: return false
    val host = uri.host?.lowercase() ?: return false
    return uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) &&
        (host == "vk.ru" || host.endsWith(".vk.ru") || host == "vk.com" || host.endsWith(".vk.com"))
}

/** Автозавершение начинается на сайте аккаунта, а не в промежуточных формах login/id/OAuth. */
private fun isVkWebLanding(value: String?): Boolean {
    if (!isVkWebUrl(value)) return false
    val host = Uri.parse(value).host
    return host in setOf("vk.ru", "www.vk.ru", "m.vk.ru", "vk.com", "www.vk.com", "m.vk.com")
}

/** Читает HttpOnly-cookies нативно, проверяя основную и login-области одной доменной семьи. */
private fun readVkWebSession(userAgent: String, pageUrl: String?): VkWebSession? {
    val manager = CookieManager.getInstance()
    val host = pageUrl?.let(Uri::parse)?.host.orEmpty()
    val domains = if (host == "vk.com" || host.endsWith(".vk.com")) listOf("vk.com", "vk.ru") else listOf("vk.ru", "vk.com")
    for (domain in domains) {
        val cookies = listOf("https://$domain/", "https://login.$domain/", "https://m.$domain/")
            .flatMap { manager.getCookie(it).orEmpty().split(';') }
            .map { it.trim().split('=', limit = 2) }
        val p = cookies.firstOrNull { it.size == 2 && it[0] == "p" && it[1].isNotBlank() }?.get(1)
        val remixsid = cookies.firstOrNull { it.size == 2 && it[0] == "remixsid" && it[1].isNotBlank() }?.get(1)
        if (p != null && remixsid != null) return VkWebSession(p, remixsid, userAgent)
    }
    return null
}

private const val WEB_TAG = "VkWebLogin"
