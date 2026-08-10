package com.yellastrodev.dwij.navigation

import androidx.compose.runtime.Composable

/**
 * Платформенные операции экрана настроек.
 *
 * OAuth, постоянные настройки и состояние авторизации принадлежат shared.
 * Платформа предоставляет только конфигурацию, сведения о диске
 * и внешние системные действия.
 */
interface SettingsPlatform {

    val oauthClientId: String

    val oauthClientSecret: String

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
