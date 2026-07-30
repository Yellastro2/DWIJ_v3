package com.yellastrodev.yandexmusiclib.network

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets

internal enum class YamHttpMethod {
    GET,
    POST
}

internal sealed interface YamHttpBody {
    data class Form(val fields: Map<String, String>) : YamHttpBody
    data class Json(val value: String) : YamHttpBody
}

internal data class YamHttpRequest(
    val method: YamHttpMethod,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val body: YamHttpBody? = null,
    val requiresAuthorization: Boolean = true
)

internal data class YamHttpResponse(
    val statusCode: Int,
    val body: String
)

internal fun interface YamTransport {
    suspend fun execute(request: YamHttpRequest): YamResult<YamHttpResponse>
}

internal fun interface YamContentTransport {
    suspend fun retrieve(
        url: String,
        requiresAuthorization: Boolean
    ): YamResult<ByteArray>
}

internal interface YamNetworkLogger {
    fun requestStarted(method: YamHttpMethod, path: String)
    fun requestFinished(method: YamHttpMethod, path: String, statusCode: Int, elapsedMs: Long)
    fun requestFailed(method: YamHttpMethod, path: String, cause: Throwable)
}

internal object AndroidYamNetworkLogger : YamNetworkLogger {
    private const val TAG = "YamNetwork"

    override fun requestStarted(method: YamHttpMethod, path: String) {
        Log.d(TAG, "[execute] Начат запрос $method $path")
    }

    override fun requestFinished(
        method: YamHttpMethod,
        path: String,
        statusCode: Int,
        elapsedMs: Long
    ) {
        Log.d(TAG, "[execute] $method $path: HTTP $statusCode, ${elapsedMs}мс")
    }

    override fun requestFailed(method: YamHttpMethod, path: String, cause: Throwable) {
        Log.e(TAG, "[execute] Ошибка запроса $method $path", cause)
    }
}

/**
 * Внутренний HTTP transport. Не отдаёт JSON наружу и не логирует тело или токен.
 */
internal class YamHttpTransport(
    private val accessToken: () -> String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 15_000,
    private val logger: YamNetworkLogger = AndroidYamNetworkLogger
) : YamTransport, YamContentTransport {

    override suspend fun execute(
        request: YamHttpRequest
    ): YamResult<YamHttpResponse> = withContext(Dispatchers.IO) {
        val token = accessToken()
        if (request.requiresAuthorization && token.isBlank()) {
            return@withContext YamResult.Failure(YamError.Unauthorized)
        }

        val startedAt = System.nanoTime()
        logger.requestStarted(request.method, request.path)

        try {
            val connection = openConnection(request)
            try {
                configureConnection(connection, request, token)
                writeBody(connection, request.body)

                val statusCode = connection.responseCode
                val responseBody = readResponseBody(connection, statusCode)
                val elapsedMs = (System.nanoTime() - startedAt) / NANOS_IN_MILLISECOND
                logger.requestFinished(
                    request.method,
                    request.path,
                    statusCode,
                    elapsedMs
                )

                if (statusCode in 200..299) {
                    YamResult.Success(YamHttpResponse(statusCode, responseBody))
                } else {
                    YamResult.Failure(mapHttpError(statusCode, responseBody))
                }
            } finally {
                connection.disconnect()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: SocketTimeoutException) {
            logger.requestFailed(request.method, request.path, error)
            YamResult.Failure(YamError.Timeout)
        } catch (error: UnknownHostException) {
            logger.requestFailed(request.method, request.path, error)
            YamResult.Failure(YamError.NoInternet)
        } catch (error: ConnectException) {
            logger.requestFailed(request.method, request.path, error)
            YamResult.Failure(YamError.NoInternet)
        } catch (error: IOException) {
            logger.requestFailed(request.method, request.path, error)
            YamResult.Failure(YamError.Network(error))
        } catch (error: Exception) {
            logger.requestFailed(request.method, request.path, error)
            YamResult.Failure(YamError.Network(error))
        }
    }

    override suspend fun retrieve(
        url: String,
        requiresAuthorization: Boolean
    ): YamResult<ByteArray> = withContext(Dispatchers.IO) {
        val token = accessToken()
        if (requiresAuthorization && token.isBlank()) {
            return@withContext YamResult.Failure(YamError.Unauthorized)
        }

        val logPath = "/external-content"
        val startedAt = System.nanoTime()
        logger.requestStarted(YamHttpMethod.GET, logPath)
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = YamHttpMethod.GET.name
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                connection.setRequestProperty("Accept", "*/*")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                if (requiresAuthorization) {
                    connection.setRequestProperty("Authorization", "OAuth $token")
                }

                val statusCode = connection.responseCode
                val elapsedMs =
                    (System.nanoTime() - startedAt) / NANOS_IN_MILLISECOND
                logger.requestFinished(
                    YamHttpMethod.GET,
                    logPath,
                    statusCode,
                    elapsedMs
                )
                if (statusCode in 200..299) {
                    YamResult.Success(connection.inputStream.use { it.readBytes() })
                } else {
                    val errorBody = connection.errorStream
                        ?.bufferedReader(StandardCharsets.UTF_8)
                        ?.use { it.readText() }
                        .orEmpty()
                    YamResult.Failure(mapHttpError(statusCode, errorBody))
                }
            } finally {
                connection.disconnect()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: SocketTimeoutException) {
            logger.requestFailed(YamHttpMethod.GET, logPath, error)
            YamResult.Failure(YamError.Timeout)
        } catch (error: UnknownHostException) {
            logger.requestFailed(YamHttpMethod.GET, logPath, error)
            YamResult.Failure(YamError.NoInternet)
        } catch (error: ConnectException) {
            logger.requestFailed(YamHttpMethod.GET, logPath, error)
            YamResult.Failure(YamError.NoInternet)
        } catch (error: IOException) {
            logger.requestFailed(YamHttpMethod.GET, logPath, error)
            YamResult.Failure(YamError.Network(error))
        } catch (error: Exception) {
            logger.requestFailed(YamHttpMethod.GET, logPath, error)
            YamResult.Failure(YamError.Network(error))
        }
    }

    private fun openConnection(request: YamHttpRequest): HttpURLConnection {
        val normalizedPath = if (request.path.startsWith("/")) {
            request.path
        } else {
            "/${request.path}"
        }
        val query = request.query
            .takeIf { it.isNotEmpty() }
            ?.entries
            ?.joinToString("&") { (key, value) ->
                "${key.urlEncoded()}=${value.urlEncoded()}"
            }
            ?.let { "?$it" }
            .orEmpty()
        return URL("$baseUrl$normalizedPath$query").openConnection() as HttpURLConnection
    }

    private fun configureConnection(
        connection: HttpURLConnection,
        request: YamHttpRequest,
        token: String
    ) {
        connection.requestMethod = request.method.name
        connection.connectTimeout = connectTimeoutMillis
        connection.readTimeout = readTimeoutMillis
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("X-Yandex-Music-Client", YANDEX_MUSIC_CLIENT)
        if (request.requiresAuthorization) {
            connection.setRequestProperty("Authorization", "OAuth $token")
        }
        when (request.body) {
            is YamHttpBody.Form -> {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded"
                )
            }
            is YamHttpBody.Json -> {
                connection.doOutput = true
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=UTF-8"
                )
            }
            null -> Unit
        }
    }

    private fun writeBody(connection: HttpURLConnection, body: YamHttpBody?) {
        val requestBody = when (body) {
            is YamHttpBody.Form -> body.fields.entries.joinToString("&") { (key, value) ->
                "${key.urlEncoded()}=${value.urlEncoded()}"
            }
            is YamHttpBody.Json -> body.value
            null -> return
        }
        connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
            writer.write(requestBody)
        }
    }

    private fun readResponseBody(
        connection: HttpURLConnection,
        statusCode: Int
    ): String {
        val stream = if (statusCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }
        return stream
            ?.bufferedReader(StandardCharsets.UTF_8)
            ?.use { it.readText() }
            .orEmpty()
    }

    private fun mapHttpError(statusCode: Int, body: String): YamError {
        if (statusCode == HttpURLConnection.HTTP_UNAUTHORIZED ||
            statusCode == HttpURLConnection.HTTP_FORBIDDEN
        ) {
            return YamError.Unauthorized
        }

        val details = parseErrorDetails(body)
        return YamError.Http(
            statusCode = statusCode,
            code = details.first,
            description = details.second
        )
    }

    private fun parseErrorDetails(body: String): Pair<String?, String?> {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val errorElement = root["error"]
            val code = runCatching {
                errorElement?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: runCatching {
                errorElement?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        val description = root["errorDescription"]?.jsonPrimitive?.contentOrNull
            ?: root["error_description"]?.jsonPrimitive?.contentOrNull
            ?: root["message"]?.jsonPrimitive?.contentOrNull
                ?: runCatching {
                    errorElement?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
            code to description
        } catch (_: Exception) {
            null to null
        }
    }

    private fun String.urlEncoded(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    private companion object {
        const val DEFAULT_BASE_URL = "https://api.music.yandex.net"
        const val USER_AGENT = "Yandex-Music-API"
        const val YANDEX_MUSIC_CLIENT = "YandexMusicAndroid/24023621"
        const val NANOS_IN_MILLISECOND = 1_000_000L
        val json = Json { ignoreUnknownKeys = true }
    }
}
