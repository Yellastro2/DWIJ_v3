package com.yellastrodev.yandexmusiclib.download

import com.yellastrodev.yandexmusiclib.network.YamContentTransport
import com.yellastrodev.yandexmusiclib.network.YamError
import com.yellastrodev.yandexmusiclib.network.YamHttpMethod
import com.yellastrodev.yandexmusiclib.network.YamHttpRequest
import com.yellastrodev.yandexmusiclib.network.YamResponseDecoder
import com.yellastrodev.yandexmusiclib.network.YamResult
import com.yellastrodev.yandexmusiclib.network.YamTransport
import kotlinx.serialization.builtins.ListSerializer
import org.xml.sax.InputSource
import java.io.StringReader
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

internal class DownloadApi(
    private val transport: YamTransport,
    private val contentTransport: YamContentTransport
) {
    suspend fun downloadInfo(trackId: String): YamResult<List<DownloadInfo>> {
        if (trackId.isBlank()) {
            return invalidResponse("trackId не должен быть пустым")
        }
        return when (
            val response = transport.execute(
                YamHttpRequest(
                    method = YamHttpMethod.GET,
                    path = "/tracks/$trackId/download-info"
                )
            )
        ) {
            is YamResult.Success -> YamResponseDecoder.decodeResult(
                response.value,
                ListSerializer(DownloadInfo.serializer())
            )
            is YamResult.Failure -> response
        }
    }

    suspend fun directDownloadUrl(trackId: String): YamResult<String> {
        return when (val infoResult = downloadInfo(trackId)) {
            is YamResult.Success -> {
                val info = infoResult.value.firstOrNull()
                    ?: return invalidResponse("Сервис не вернул варианты загрузки")
                when (
                    val xml = contentTransport.retrieve(
                        url = info.downloadInfoUrl,
                        requiresAuthorization = false
                    )
                ) {
                    is YamResult.Success -> buildDirectUrl(xml.value)
                    is YamResult.Failure -> xml
                }
            }
            is YamResult.Failure -> infoResult
        }
    }

    suspend fun downloadBytes(trackId: String): YamResult<ByteArray> =
        when (val urlResult = directDownloadUrl(trackId)) {
            is YamResult.Success -> contentTransport.retrieve(
                url = urlResult.value,
                requiresAuthorization = false
            )
            is YamResult.Failure -> urlResult
        }

    private fun buildDirectUrl(xml: ByteArray): YamResult<String> {
        return try {
            val xmlText = xml.toString(Charsets.UTF_8)
            if (
                xmlText.contains("<!DOCTYPE", ignoreCase = true) ||
                xmlText.contains("<!ENTITY", ignoreCase = true)
            ) {
                return invalidResponse(
                    "DOCTYPE и ENTITY запрещены в download-info XML"
                )
            }
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
                runCatching { setXIncludeAware(false) }
                runCatching { isExpandEntityReferences = false }
                runCatching {
                    setFeature(
                        "http://apache.org/xml/features/disallow-doctype-decl",
                        true
                    )
                }
                runCatching {
                    setFeature(
                        "http://xml.org/sax/features/external-general-entities",
                        false
                    )
                }
                runCatching {
                    setFeature(
                        "http://xml.org/sax/features/external-parameter-entities",
                        false
                    )
                }
                runCatching {
                    setFeature(
                        "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                        false
                    )
                }
            }
            val document = factory.newDocumentBuilder()
                .apply {
                    setEntityResolver { _, _ ->
                        InputSource(StringReader(""))
                    }
                }
                .parse(xml.inputStream())
            val host = document.text("host")
            val path = document.text("path")
            val timestamp = document.text("ts")
            val saltValue = document.text("s")
            if (!path.startsWith("/")) {
                return invalidResponse("Некорректный path в download-info XML")
            }
            val sign = md5("$SIGN_SALT${path.substring(1)}$saltValue")
            YamResult.Success(
                "https://$host/get-mp3/$sign/$timestamp$path"
            )
        } catch (error: Exception) {
            YamResult.Failure(YamError.InvalidResponse(error))
        }
    }

    private fun org.w3c.dom.Document.text(tag: String): String {
        val value = getElementsByTagName(tag)
            .item(0)
            ?.textContent
            ?.takeIf { it.isNotBlank() }
        return value ?: throw IllegalArgumentException(
            "В download-info XML отсутствует $tag"
        )
    }

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }

    private fun invalidResponse(message: String): YamResult.Failure =
        YamResult.Failure(
            YamError.InvalidResponse(
                IllegalArgumentException(message)
            )
        )

    private companion object {
        const val SIGN_SALT = "XGRlBW9FXlekgbPrRHuSiA"
    }
}
