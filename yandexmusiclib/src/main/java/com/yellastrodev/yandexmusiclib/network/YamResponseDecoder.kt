package com.yellastrodev.yandexmusiclib.network

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
private data class YamResponseEnvelope<T>(
    val result: T? = null
)

internal object YamResponseDecoder {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun <T> decodeResult(
        response: YamHttpResponse,
        resultSerializer: KSerializer<T>
    ): YamResult<T> = try {
        val envelopeSerializer: KSerializer<YamResponseEnvelope<T>> =
            YamResponseEnvelope.serializer(resultSerializer)
        val envelope: YamResponseEnvelope<T> = json.decodeFromString(
            deserializer = envelopeSerializer,
            string = response.body
        )
        envelope.result?.let { YamResult.Success(it) }
            ?: YamResult.Failure(
                YamError.InvalidResponse(
                    IllegalArgumentException("В ответе отсутствует result")
                )
            )
    } catch (error: SerializationException) {
        YamResult.Failure(YamError.InvalidResponse(error))
    } catch (error: IllegalArgumentException) {
        YamResult.Failure(YamError.InvalidResponse(error))
    }

    fun <T> decodeBody(
        response: YamHttpResponse,
        serializer: KSerializer<T>
    ): YamResult<T> = try {
        YamResult.Success(
            json.decodeFromString(
                deserializer = serializer,
                string = response.body
            )
        )
    } catch (error: SerializationException) {
        YamResult.Failure(YamError.InvalidResponse(error))
    } catch (error: IllegalArgumentException) {
        YamResult.Failure(YamError.InvalidResponse(error))
    }

    fun decodeResultElement(response: YamHttpResponse): YamResult<JsonElement> =
        decodeResult(response, JsonElement.serializer())

    fun <T> decodeElement(
        element: JsonElement,
        serializer: KSerializer<T>
    ): YamResult<T> = try {
        YamResult.Success(json.decodeFromJsonElement(serializer, element))
    } catch (error: SerializationException) {
        YamResult.Failure(YamError.InvalidResponse(error))
    } catch (error: IllegalArgumentException) {
        YamResult.Failure(YamError.InvalidResponse(error))
    }
}
