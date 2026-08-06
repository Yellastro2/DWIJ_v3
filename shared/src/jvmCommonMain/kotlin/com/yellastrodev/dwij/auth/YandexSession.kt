package com.yellastrodev.dwij.auth

/** Сохранённая авторизация Яндекс Музыки. */
data class YandexSession(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMillis: Long?,
    val login: String?,
    val userId: String?,
)

/** Платформенное постоянное хранилище авторизации. */
interface YandexSessionStore {
    fun read(): YandexSession?

    fun write(session: YandexSession)

    fun clear()
}
