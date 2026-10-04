package com.yellastrodev.dwij.navigation

import androidx.compose.runtime.Composable

/**
 * Платформенные операции экрана настроек.
 *
 * OAuth, постоянные настройки и состояние авторизации принадлежат shared.
 * Платформа предоставляет только конфигурацию, сведения о диске
 * внешние системные действия и отдельные Android-окна OAuth и cookie-авторизации VK.
 */
interface SettingsPlatform {

    /** Отображаемая версия текущей платформенной сборки. */
    val appVersion: String

    val oauthClientId: String

    val oauthClientSecret: String

    /** Показывает действие экспорта журналов только на поддерживаемых платформах. */
    val canShareLogs: Boolean
        get() = false

    /** Desktop-only список каталогов; null скрывает управление на платформе. */
    val musicDirectories: List<String>?
        get() = null

    fun availableCacheBytes(): Long

    fun copyText(
        label: String,
        text: String,
    )

    fun openUrl(
        url: String,
    ): Boolean

    /** Android предлагает собственный браузер входа; остальные платформы используют ручной callback. */
    val hasEmbeddedVkLogin: Boolean get() = false

    /** Браузерная cookie-сессия пока поддерживается только Android. */
    val hasVkWebLogin: Boolean get() = false

    /** Передаёт браузерную сессию только в памяти; busy и error отражают проверку в shared. */
    @Composable
    fun VkWebLoginBrowser(onSession: (com.yellastrodev.vkmusicsdk.VkWebSession) -> Unit,
        onDismiss: () -> Unit, busy: Boolean, error: String?) = Unit

    /** Временный сброс браузерной сессии доступен только в Android debug. */
    val canResetVkBrowserSession: Boolean get() = false

    /** Очищает браузерные данные, не затрагивая сохранённую авторизацию SDK. */
    suspend fun resetVkBrowserSession() = Unit

    /** Показывает платформенный браузер; callback возвращает URL только в памяти, fallback открывает внешний браузер. */
    @Composable
    fun VkLoginBrowser(url: String, onRedirect: (String) -> Unit, onDismiss: () -> Unit,
        onOpenExternalBrowser: () -> Unit) = Unit

    /** Создаёт диагностический архив и передаёт его в платформенный экспорт. */
    suspend fun shareLogs(
        chooserTitle: String,
    ) = Unit

    /** Платформа может немедленно возобновить ожидающую сетевую работу после входа. */
    fun onYandexAuthorizationSaved() = Unit

    /** Открывает системный выбор каталога и возвращает выбранный путь. */
    fun chooseMusicDirectory(
        dialogTitle: String,
    ): String? = null

    /** Сохраняет полный новый список и возвращает нормализованные пути. */
    fun replaceMusicDirectories(
        directories: List<String>,
    ): List<String>? = null

    /**
     * Вызывает [onResume] при последующих возвратах приложения на экран.
     */
    @Composable
    fun ResumeEffect(
        onResume: () -> Unit,
    )
}
