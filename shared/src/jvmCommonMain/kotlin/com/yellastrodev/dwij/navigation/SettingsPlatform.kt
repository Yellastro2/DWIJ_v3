package com.yellastrodev.dwij.navigation

import androidx.compose.runtime.Composable

/**
 * Платформенные операции экрана настроек.
 *
 * OAuth, постоянные настройки и состояние авторизации принадлежат shared.
 * Платформа предоставляет только конфигурацию, сведения о диске
 * внешние системные действия и Android-окно браузерной авторизации.
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
