package com.yellastrodev.dwij.navigation

import androidx.compose.runtime.Composable

/** Данные авторизации, которые shared-route сохраняет через платформу. */
data class SettingsYandexSession(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMillis: Long?,
    val login: String,
    val userId: String,
)

/**
 * Платформенные операции экрана настроек.
 *
 * OAuth и обновление репозиториев выполняются в shared. Платформа предоставляет
 * конфигурацию, постоянное хранилище, сведения о диске и внешние действия.
 */
interface SettingsPlatform {
    val oauthClientId: String

    val oauthClientSecret: String

    fun readYandexLogin(): String?

    fun saveYandexSession(session: SettingsYandexSession)

    fun clearYandexSession()

    fun readCacheLimitBytes(): Long

    fun writeCacheLimitBytes(bytes: Long)

    fun availableCacheBytes(): Long

    fun copyText(
        label: String,
        text: String,
    )

    fun openUrl(url: String): Boolean

    /** Вызывает [onResume] при последующих возвратах приложения на экран. */
    @Composable
    fun ResumeEffect(onResume: () -> Unit)
}
