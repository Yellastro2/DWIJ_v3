package com.yellastrodev.yandexmusiclib.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Код устройства, который пользователь подтверждает на странице OAuth.
 */
@Serializable
data class DeviceCode(
    @SerialName("device_code")
    val deviceCode: String,
    @SerialName("user_code")
    val userCode: String,
    @SerialName("verification_url")
    val verificationUrl: String,
    @SerialName("expires_in")
    val expiresIn: Long,
    val interval: Long
)

/**
 * Набор токенов, возвращаемый после подтверждения Device Flow.
 */
@Serializable
data class OAuthToken(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("expires_in")
    val expiresIn: Long? = null,
    @SerialName("token_type")
    val tokenType: String? = null
)

/**
 * Типизированный результат операции Device Flow.
 */
sealed interface DeviceAuthResult<out T> {
    data class Success<T>(val value: T) : DeviceAuthResult<T>
    data class Failure(val error: DeviceAuthError) : DeviceAuthResult<Nothing>
}

/**
 * Ошибки, которые вызывающий код должен явно обработать.
 *
 * Отмена корутины не преобразуется в ошибку и распространяется штатно.
 */
sealed interface DeviceAuthError {
    data object Configuration : DeviceAuthError
    data object Cancelled : DeviceAuthError
    data class Timeout(val timeoutSeconds: Long) : DeviceAuthError
    data class Network(val cause: Throwable) : DeviceAuthError
    data class OAuth(val code: String, val description: String?) : DeviceAuthError
    data class Http(val statusCode: Int) : DeviceAuthError
    data class InvalidResponse(val cause: Throwable) : DeviceAuthError
}
