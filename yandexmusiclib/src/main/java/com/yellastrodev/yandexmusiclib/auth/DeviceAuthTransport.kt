package com.yellastrodev.yandexmusiclib.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal data class OAuthHttpResponse(
    val statusCode: Int,
    val body: String
)

internal fun interface DeviceAuthTransport {
    suspend fun postForm(url: String, fields: Map<String, String>): OAuthHttpResponse
}

internal class HttpUrlConnectionDeviceAuthTransport(
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 15_000
) : DeviceAuthTransport {

    override suspend fun postForm(
        url: String,
        fields: Map<String, String>
    ): OAuthHttpResponse = withContext(Dispatchers.IO) {
        val body = fields.entries.joinToString("&") { (key, value) ->
            "${key.urlEncoded()}=${value.urlEncoded()}"
        }
        val connection = URL(url).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8"
            )
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("X-Yandex-Music-Client", YANDEX_MUSIC_CLIENT)

            connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.write(body)
            }

            val statusCode = connection.responseCode
            val responseStream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val responseBody = responseStream
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            OAuthHttpResponse(statusCode, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun String.urlEncoded(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    private companion object {
        const val USER_AGENT = "Yandex-Music-API"
        const val YANDEX_MUSIC_CLIENT = "YandexMusicAndroid/24023621"
    }
}
