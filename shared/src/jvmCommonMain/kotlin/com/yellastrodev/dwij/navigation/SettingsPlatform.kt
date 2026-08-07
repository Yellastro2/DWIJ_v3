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

    fun availableCacheBytes(): Long

    fun copyText(
        label: String,
        text: String,
    )

    fun openUrl(
        url: String,
    ): Boolean

    /**
     * Вызывает [onResume] при последующих возвратах приложения на экран.
     */
    @Composable
    fun ResumeEffect(
        onResume: () -> Unit,
    )
}