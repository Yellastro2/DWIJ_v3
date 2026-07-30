package com.yellastrodev.yandexmusiclib.auth

import android.util.Log
import com.yellastrodev.yandexmusiclib.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.SecureRandom

/**
 * Логирование Device Flow без токенов, кодов подтверждения и OAuth-секретов.
 */
interface DeviceAuthLogger {
    fun debug(message: String)
    fun warning(message: String)
    fun error(message: String, cause: Throwable? = null)
}

object AndroidDeviceAuthLogger : DeviceAuthLogger {
    private const val TAG = "YandexDeviceAuth"

    override fun debug(message: String) {
        Log.d(TAG, message)
    }

    override fun warning(message: String) {
        Log.w(TAG, message)
    }

    override fun error(message: String, cause: Throwable?) {
        Log.e(TAG, message, cause)
    }
}

/**
 * OAuth Device Flow, совместимый с поведением локальной Python SDK.
 *
 * Класс можно использовать целиком через [authorize] либо вызывать
 * [requestDeviceCode] и [pollDeviceToken] по отдельности.
 */
class YandexDeviceAuth internal constructor(
    private val clientId: String,
    private val clientSecret: String,
    private val deviceName: String,
    private val logger: DeviceAuthLogger,
    private val transport: DeviceAuthTransport,
    private val nowMillis: () -> Long,
    private val delayMillis: suspend (Long) -> Unit,
    private val deviceIdFactory: () -> String
) {

    @JvmOverloads
    constructor(
        clientId: String = BuildConfig.YANDEX_OAUTH_CLIENT_ID,
        clientSecret: String = BuildConfig.YANDEX_OAUTH_CLIENT_SECRET,
        deviceName: String = DEFAULT_DEVICE_NAME,
        logger: DeviceAuthLogger = AndroidDeviceAuthLogger
    ) : this(
        clientId = clientId,
        clientSecret = clientSecret,
        deviceName = deviceName,
        logger = logger,
        transport = HttpUrlConnectionDeviceAuthTransport(),
        nowMillis = { System.nanoTime() / NANOS_IN_MILLISECOND },
        delayMillis = { delay(it) },
        deviceIdFactory = { randomDeviceId() }
    )

    /**
     * Запрашивает пользовательский код и параметры ожидания подтверждения.
     */
    suspend fun requestDeviceCode(
        deviceId: String? = null
    ): DeviceAuthResult<DeviceCode> {
        configurationError(requireClientSecret = false)?.let {
            return DeviceAuthResult.Failure(it)
        }
        currentCoroutineContext().ensureActive()
        logger.debug("[requestDeviceCode] Запрашиваем код устройства")

        val response = when (
            val result = postForm(
                url = "$OAUTH_BASE_URL/device/code",
                fields = mapOf(
                    "client_id" to clientId,
                    "device_id" to (deviceId ?: deviceIdFactory()),
                    "device_name" to deviceName
                )
            )
        ) {
            is DeviceAuthResult.Success -> result.value
            is DeviceAuthResult.Failure -> return result
        }

        parseOAuthError(response)?.let { return DeviceAuthResult.Failure(it) }
        if (response.statusCode !in 200..299) {
            logger.warning("[requestDeviceCode] Неожиданный HTTP ${response.statusCode}")
            return DeviceAuthResult.Failure(DeviceAuthError.Http(response.statusCode))
        }

        return decode<DeviceCode>(response.body)
            .flatMap { code ->
                if (
                    code.deviceCode.isBlank() ||
                    code.userCode.isBlank() ||
                    code.verificationUrl.isBlank() ||
                    code.expiresIn <= 0 ||
                    code.interval <= 0
                ) {
                    invalidResponse("Некорректные поля device code")
                } else {
                    logger.debug(
                        "[requestDeviceCode] Код получен: expiresIn=${code.expiresIn}, " +
                            "interval=${code.interval}"
                    )
                    DeviceAuthResult.Success(code)
                }
            }
    }

    /**
     * Однократно проверяет подтверждение кода.
     *
     * `Success(null)` означает `authorization_pending`.
     */
    suspend fun pollDeviceToken(
        deviceCode: String
    ): DeviceAuthResult<OAuthToken?> {
        configurationError(requireClientSecret = true)?.let {
            return DeviceAuthResult.Failure(it)
        }
        if (deviceCode.isBlank()) {
            return invalidResponse("Пустой device code")
        }
        currentCoroutineContext().ensureActive()

        val response = when (
            val result = postForm(
                url = "$OAUTH_BASE_URL/token",
                fields = mapOf(
                    "grant_type" to "device_code",
                    "code" to deviceCode,
                    "client_id" to clientId,
                    "client_secret" to clientSecret
                )
            )
        ) {
            is DeviceAuthResult.Success -> result.value
            is DeviceAuthResult.Failure -> return result
        }

        parseOAuthError(response)?.let { error ->
            if (error.code == AUTHORIZATION_PENDING) {
                logger.debug("[pollDeviceToken] Подтверждение ещё ожидается")
                return DeviceAuthResult.Success(null)
            }
            logger.warning("[pollDeviceToken] OAuth-ошибка: ${error.code}")
            return DeviceAuthResult.Failure(error)
        }

        if (response.statusCode !in 200..299) {
            logger.warning("[pollDeviceToken] Неожиданный HTTP ${response.statusCode}")
            return DeviceAuthResult.Failure(DeviceAuthError.Http(response.statusCode))
        }

        return decode<OAuthToken>(response.body)
            .flatMap { token ->
                if (token.accessToken.isBlank()) {
                    invalidResponse("Пустой access token")
                } else {
                    logger.debug("[pollDeviceToken] Токен получен")
                    DeviceAuthResult.Success(token)
                }
            }
    }

    /**
     * Запрашивает код и опрашивает OAuth до подтверждения, таймаута или отмены.
     *
     * Отмена корутины распространяется как [CancellationException].
     */
    suspend fun authorize(
        onCode: suspend (DeviceCode) -> Unit,
        pollIntervalSeconds: Long? = null,
        timeoutSeconds: Long? = null,
        shouldCancel: () -> Boolean = { false },
        deviceId: String? = null
    ): DeviceAuthResult<OAuthToken> {
        val code = when (val result = requestDeviceCode(deviceId)) {
            is DeviceAuthResult.Success -> result.value
            is DeviceAuthResult.Failure -> return result
        }

        onCode(code)

        val interval = pollIntervalSeconds ?: code.interval
        val timeout = timeoutSeconds ?: code.expiresIn
        if (interval <= 0 || timeout <= 0) {
            return invalidResponse("Некорректный интервал или таймаут")
        }
        val deadline = nowMillis() + timeout * MILLIS_IN_SECOND

        while (true) {
            currentCoroutineContext().ensureActive()
            if (shouldCancel()) {
                logger.debug("[authorize] Авторизация отменена вызывающим кодом")
                return DeviceAuthResult.Failure(DeviceAuthError.Cancelled)
            }

            when (val tokenResult = pollDeviceToken(code.deviceCode)) {
                is DeviceAuthResult.Success -> {
                    tokenResult.value?.let { return DeviceAuthResult.Success(it) }
                }
                is DeviceAuthResult.Failure -> return tokenResult
            }

            if (nowMillis() >= deadline) {
                logger.warning("[authorize] Истёк таймаут ожидания подтверждения")
                return DeviceAuthResult.Failure(DeviceAuthError.Timeout(timeout))
            }

            delayMillis(interval * MILLIS_IN_SECOND)
        }
    }

    private suspend fun postForm(
        url: String,
        fields: Map<String, String>
    ): DeviceAuthResult<OAuthHttpResponse> {
        return try {
            DeviceAuthResult.Success(transport.postForm(url, fields))
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            logger.error("[postForm] Ошибка сети", error)
            DeviceAuthResult.Failure(DeviceAuthError.Network(error))
        } catch (error: Exception) {
            logger.error("[postForm] Не удалось выполнить запрос", error)
            DeviceAuthResult.Failure(DeviceAuthError.Network(error))
        }
    }

    private fun configurationError(
        requireClientSecret: Boolean
    ): DeviceAuthError.Configuration? {
        if (clientId.isNotBlank() && (!requireClientSecret || clientSecret.isNotBlank())) {
            return null
        }
        logger.error("[configuration] OAuth client_id или client_secret не настроены")
        return DeviceAuthError.Configuration
    }

    private inline fun <reified T> decode(body: String): DeviceAuthResult<T> {
        return try {
            DeviceAuthResult.Success(json.decodeFromString<T>(body))
        } catch (error: SerializationException) {
            logger.error("[decode] Некорректный JSON-ответ OAuth", error)
            DeviceAuthResult.Failure(DeviceAuthError.InvalidResponse(error))
        } catch (error: IllegalArgumentException) {
            logger.error("[decode] Некорректный ответ OAuth", error)
            DeviceAuthResult.Failure(DeviceAuthError.InvalidResponse(error))
        }
    }

    private fun parseOAuthError(response: OAuthHttpResponse): DeviceAuthError.OAuth? {
        val error = try {
            json.decodeFromString<OAuthErrorResponse>(response.body)
        } catch (_: Exception) {
            return null
        }
        return error.error
            ?.takeIf { it.isNotBlank() }
            ?.let { DeviceAuthError.OAuth(it, error.description) }
    }

    private fun <T, R> DeviceAuthResult<T>.flatMap(
        transform: (T) -> DeviceAuthResult<R>
    ): DeviceAuthResult<R> = when (this) {
        is DeviceAuthResult.Success -> transform(value)
        is DeviceAuthResult.Failure -> this
    }

    private fun <T> invalidResponse(message: String): DeviceAuthResult<T> {
        val error = IllegalArgumentException(message)
        logger.error("[validate] $message", error)
        return DeviceAuthResult.Failure(DeviceAuthError.InvalidResponse(error))
    }

    @Serializable
    private data class OAuthErrorResponse(
        val error: String? = null,
        @SerialName("error_description")
        val description: String? = null
    )

    companion object {
        private const val DEFAULT_DEVICE_NAME = "YandexMusicAPI"
        private const val OAUTH_BASE_URL = "https://oauth.yandex.ru"
        private const val AUTHORIZATION_PENDING = "authorization_pending"
        private const val DEVICE_ID_LENGTH = 10
        private const val MILLIS_IN_SECOND = 1_000L
        private const val NANOS_IN_MILLISECOND = 1_000_000L
        private const val ALPHANUMERIC =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        private val secureRandom = SecureRandom()
        private val json = Json { ignoreUnknownKeys = true }

        private fun randomDeviceId(): String = buildString(DEVICE_ID_LENGTH) {
            repeat(DEVICE_ID_LENGTH) {
                append(ALPHANUMERIC[secureRandom.nextInt(ALPHANUMERIC.length)])
            }
        }
    }
}
