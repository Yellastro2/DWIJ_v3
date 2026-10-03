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

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.yellastrodev.dwij.BuildConfig
import com.yellastrodev.dwij.ui.theme.DwijColors
import com.yellastrodev.vkmusicsdk.VkOAuth
import com.yellastrodev.vkmusicsdk.VkRedirectException
import com.yellastrodev.vkmusicsdk.VkRedirectFailure


/** Android OAuth: один повтор после VK ID payload или выхода на главную аккаунта, без очистки cookies. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun AndroidVkLoginBrowser(url: String, onRedirect: (String) -> Unit,
    onDismiss: () -> Unit, onOpenExternalBrowser: () -> Unit) {
    val context = LocalContext.current
    val currentRedirect = rememberUpdatedState(onRedirect)
    var loading by remember(url) { mutableStateOf(true) }
    var completed by remember(url) { mutableStateOf(false) }
    var restartPending by remember(url) { mutableStateOf(false) }
    var oauthRestarted by remember(url) { mutableStateOf(false) }

    /** Распознаёт наблюдавшийся выход из OAuth на account/#/main с account_action_redirect_url. */
    fun consumeAccountLanding(candidate: String?): Boolean {
        if (candidate == null || completed || restartPending || oauthRestarted) return false
        val page = Uri.parse(candidate)
        val fragment = page.fragment.orEmpty()
        val route = fragment.substringBefore('?')
        val fragmentKeys = fragment.substringAfter('?', "").split('&').map { it.substringBefore('=') }
        if (page.scheme != "https" || page.host !in setOf("id.vk.ru", "id.vk.com") ||
            page.encodedAuthority != page.host || page.path?.trimEnd('/') != "/account" ||
            route != "/main" || "account_action_redirect_url" !in fragmentKeys) return false
        oauthRestarted = true
        restartPending = true
        loading = true
        Log.i(TAG, "[restartVkLogin] VK ID перешёл на account/#/main вместо OAuth callback; однократно открываем исходный OAuth с сохранёнными cookies")
        return true
    }


    /** Логирует callback и один раз повторяет OAuth после проверенного payload; остальные ответы передаёт карточке. */
    fun consumeRedirect(candidate: String?): Boolean {
        if (candidate == null || !VkOAuth.isRedirectUrl(candidate)) return false
        if (restartPending) return true
        if (!completed) {
            val fragment = Uri.parse(candidate).encodedFragment.orEmpty()
            val kind = when {
                fragment.startsWith("access_token=") || fragment.contains("&access_token=") -> "access_token"
                fragment.startsWith("payload=") || fragment.contains("&payload=") -> "payload VK ID"
                else -> "без токена"
            }
            Log.i(TAG, "[consumeVkRedirect] Получен callback: тип=$kind")
            // ВРЕМЕННО: удалить после диагностики VK ID. Callback содержит секреты.
            if (true) {
                val chunks = candidate.chunked(2000)
                chunks.forEachIndexed { index, chunk ->
                    Log.d(TAG, "[debugVkRedirect] ВРЕМЕННАЯ полная ссылка ${index + 1}/${chunks.size}: $chunk")
                }
            }
            val expectedState = Uri.parse(url).getQueryParameter("state")
            val states = fragment.split('&').filter { it.substringBefore('=') == "state" }
                .map { Uri.decode(it.substringAfter('=', "")) }
            val verifiedPayload = !expectedState.isNullOrBlank() && states.singleOrNull() == expectedState &&
                try {
                    VkOAuth.parseAutomaticRedirect(candidate, expectedState)
                    false
                } catch (error: VkRedirectException) {
                    error.reason == VkRedirectFailure.VkIdPayload
                }
            if (verifiedPayload && !oauthRestarted) {
                oauthRestarted = true
                restartPending = true
                loading = true
                Log.i(TAG, "[restartVkLogin] Получен VK ID payload с совпадающим state; однократно повторяем исходный OAuth с сохранёнными cookies")
            } else {
                completed = true
                currentRedirect.value(candidate)
            }
        }
        return true
    }

    val browser = remember(url, context) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            configureVkDesktopMode()
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    Log.d(TAG, "[layoutVkLogin] Размер WebView: ширина=${view.width}, высота=${view.height}, прокруткаY=${view.scrollY}")
                }
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                /** Отмечает достижение полной загрузки документа без вывода содержимого страницы. */
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress == 100) Log.d(TAG,
                        "[traceVkPage] событие=progress100; ${vkPageDescription(view?.url)}")
                }
                /** Не передаёт console-вывод сторонней страницы в логи приложения. */
                override fun onConsoleMessage(message: ConsoleMessage?): Boolean = true
            }
            webViewClient = object : WebViewClient() {
                /** Callback остаётся в приложении; intent/VK-ссылки открывают внешнее приложение или HTTPS fallback. */
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    if (request == null || !request.isForMainFrame) return false
                    val target = request.url.toString()
                    Log.d(TAG, "[traceVkPage] событие=navigate; жест=${request.hasGesture()}; ${vkPageDescription(target)}")
                    if (consumeRedirect(target)) return true
                    if (request.url.scheme != "https") {
                        loading = false
                        val sourceHost = view?.url?.let { Uri.parse(it).host }
                        if (!isVkLoginHost(sourceHost)) {
                            Log.w(TAG, "[navigateVkLogin] Внешний переход отклонён: источник вне VK, схема=${request.url.scheme}")
                            return true
                        }
                        when (val navigation = openVkExternalLink(context, target)) {
                            is VkExternalNavigation.Opened -> Unit
                            is VkExternalNavigation.Fallback -> {
                                loading = true
                                if (!consumeRedirect(navigation.url)) view?.loadUrl(navigation.url)
                            }
                            is VkExternalNavigation.Unavailable -> Unit
                        }
                        return true
                    }
                    return false
                }

                /** Старт документа логирует путь без значений параметров и также ловит callback. */
                override fun onPageStarted(view: WebView?, target: String?, favicon: Bitmap?) {
                    Log.d(TAG, "[traceVkPage] событие=started; ${vkPageDescription(target)}")
                    if (consumeRedirect(target)) { view?.stopLoading(); return }
                    if (completed) return
                    loading = true
                    Log.d(TAG, "[loadVkLoginPage] Загрузка страницы: host=${target?.let { Uri.parse(it).host }.orEmpty()}")
                }

                /** Завершение ловит callback при изменении hash; полная ссылка никуда не сохраняется. */
                override fun onPageFinished(view: WebView?, target: String?) {
                    Log.d(TAG, "[traceVkPage] событие=finished; ${vkPageDescription(target)}")
                    if (consumeAccountLanding(target)) return
                    if (!consumeRedirect(target) && !completed) {
                        loading = false
                        Log.d(TAG, "[layoutVkLogin] Страница загружена: ширина=${view?.width}, высота=${view?.height}, высотаКонтента=${view?.contentHeight}, прокруткаY=${view?.scrollY}")
                    }
                }

                /** Ловит изменения URL средствами JS без обязательной загрузки нового документа. */
                override fun doUpdateVisitedHistory(view: WebView?, target: String?, isReload: Boolean) {
                    Log.d(TAG, "[traceVkPage] событие=history; reload=$isReload; ${vkPageDescription(target)}")
                    if (consumeAccountLanding(target)) return
                    consumeRedirect(target)
                }

                /** Отмечает появление нового документа на экране, не гарантируя завершение JS-приложения VK. */
                override fun onPageCommitVisible(view: WebView?, target: String?) {
                    Log.d(TAG, "[traceVkPage] событие=visible; ${vkPageDescription(target)}")
                }

                /** Ошибки основного документа показываются без описания WebView, которое может содержать URL. */
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, failure: WebResourceError?) {
                    if (request?.isForMainFrame != true || completed) return
                    loading = false
                    Log.w(TAG, "[loadVkLoginPage] Сетевая ошибка: код=${failure?.errorCode}")
                }

                /** Ошибка сертификата отменяет запрос; пользователь может повторить вход через браузер. */
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, failure: SslError?) {
                    handler?.cancel()
                    if (completed) return
                    loading = false
                    Log.w(TAG, "[loadVkLoginPage] Ошибка TLS: код=${failure?.primaryError}")
                }
            }
        }
    }

    LaunchedEffect(browser, restartPending) {
        if (restartPending && !completed) {
            browser.stopLoading()
            CookieManager.getInstance().flush()
            restartPending = false
            browser.loadUrl(url)
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
        usePlatformDefaultWidth = false, decorFitsSystemWindows = true,
    )) {
        Column(Modifier.fillMaxSize().background(DwijColors.Background)) {
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp),
                        color = DwijColors.CyanBright, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = onOpenExternalBrowser) {
                    Text("Внешний браузер", color = DwijColors.CyanBright)
                }
            }
            AndroidView(factory = { browser }, modifier = Modifier.fillMaxWidth().weight(1f))
        }
    }
}

/** Описывает маршрут и имена параметров; значения query/fragment, userInfo и полный URL не выводит. */
private fun vkPageDescription(value: String?): String {
    if (value == null) return "страница=неизвестна"
    return try {
        val uri = Uri.parse(value)
        val queryKeys = uri.encodedQuery.orEmpty().split('&').filter { it.isNotBlank() }
            .map { it.substringBefore('=').take(60) }.distinct().take(20).joinToString(",")
        val fragmentKeys = uri.encodedFragment.orEmpty().split('&').filter { it.isNotBlank() }
            .map { if ('=' in it) it.substringBefore('=').take(60) else "маршрут" }
            .distinct().take(20).joinToString(",")
        "страница=${uri.scheme}://${uri.host.orEmpty()}${uri.encodedPath.orEmpty().take(200)}; query=[$queryKeys]; fragment=[$fragmentKeys]"
    } catch (_: Exception) { "страница=не удалось разобрать" }
}

/** Подменяет платформу на Windows, сохраняя фактическую версию Chromium установленного WebView. */
internal fun vkDesktopUserAgent(original: String): String {
    val chromeVersion = Regex("Chrome/([0-9.]+)").find(original)?.groupValues?.get(1)
        ?: return original.replace("; wv", "").replace(" Mobile", "")
    return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
}

/** Задаёт desktop UA/Client Hints до запроса; форма использует ширину WebView без overview-масштабирования. */
private fun WebView.configureVkDesktopMode() {
    val original = WebSettings.getDefaultUserAgent(context)
    val version = Regex("Chrome/([0-9.]+)").find(original)?.groupValues?.get(1)
    settings.userAgentString = vkDesktopUserAgent(original)
    settings.useWideViewPort = false
    settings.loadWithOverviewMode = false
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    val supported = WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)
    val formFactorsSupported = WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA_FORM_FACTORS)
    if (!supported) {
        Log.w(TAG, "[configureVkDesktopMode] Desktop UA включён; Client Hints не поддерживаются установленным WebView")
        return
    }
    try {
        val metadata = UserAgentMetadata.Builder()
            .setPlatform("Windows")
            .setPlatformVersion("10.0.0")
            .setMobile(false)
            .setModel("")
            .setArchitecture("x86")
            .setBitness(64)
            .setWow64(false)
        if (version != null) {
            metadata.setFullVersion(version)
            metadata.setBrandVersionList(listOf("Chromium", "Google Chrome").map { brand ->
                UserAgentMetadata.BrandVersion.Builder().setBrand(brand)
                    .setMajorVersion(version.substringBefore('.')).setFullVersion(version).build()
            })
        }
        if (formFactorsSupported) metadata.setFormFactors(listOf(UserAgentMetadata.FORM_FACTOR_DESKTOP))
        WebSettingsCompat.setUserAgentMetadata(settings, metadata.build())
        Log.i(TAG, "[configureVkDesktopMode] Desktop включён: Chrome=$version; Client Hints=true; formFactorDesktop=$formFactorsSupported")
    } catch (error: Exception) {
        Log.w(TAG, "[configureVkDesktopMode] Desktop UA включён; ошибка Client Hints: тип=${error.javaClass.simpleName}")
    }
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
