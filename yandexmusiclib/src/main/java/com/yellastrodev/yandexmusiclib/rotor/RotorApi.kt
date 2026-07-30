package com.yellastrodev.yandexmusiclib.rotor

import com.yellastrodev.yandexmusiclib.network.YamError
import com.yellastrodev.yandexmusiclib.network.YamHttpBody
import com.yellastrodev.yandexmusiclib.network.YamHttpMethod
import com.yellastrodev.yandexmusiclib.network.YamHttpRequest
import com.yellastrodev.yandexmusiclib.network.YamResponseDecoder
import com.yellastrodev.yandexmusiclib.network.YamResult
import com.yellastrodev.yandexmusiclib.network.YamTransport
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal

internal class RotorApi(
    private val transport: YamTransport,
    private val timestampSeconds: () -> Double = {
        System.currentTimeMillis() / MILLIS_IN_SECOND
    }
) {
    suspend fun tracks(
        station: String,
        queue: String? = null,
        settings2: Boolean = true
    ): YamResult<RotorBatch> {
        if (station.isBlank() || queue?.isBlank() == true) {
            return invalidArguments("station и queue не должны быть пустыми")
        }

        // В Python SDK queue заменяет settings2 в query, а не дополняет его.
        val query = when {
            queue != null -> mapOf("queue" to queue)
            settings2 -> mapOf("settings2" to "True")
            else -> emptyMap()
        }
        return when (
            val response = transport.execute(
                YamHttpRequest(
                    method = YamHttpMethod.GET,
                    path = "/rotor/station/$station/tracks",
                    query = query
                )
            )
        ) {
            is YamResult.Success -> when (
                val decoded = YamResponseDecoder.decodeResult(
                    response.value,
                    RotorTracksPayload.serializer()
                )
            ) {
                is YamResult.Success -> YamResult.Success(
                    RotorBatch(
                        station = station,
                        batchId = decoded.value.batchId,
                        tracks = decoded.value.sequence.map { it.track }
                    )
                )
                is YamResult.Failure -> decoded
            }
            is YamResult.Failure -> response
        }
    }

    suspend fun feedbackSource(
        station: String,
        language: String = "ru"
    ): YamResult<String> {
        if (station.isBlank() || language.isBlank()) {
            return invalidArguments("station и language не должны быть пустыми")
        }
        return when (
            val response = transport.execute(
                YamHttpRequest(
                    method = YamHttpMethod.GET,
                    path = "/rotor/stations/list",
                    query = mapOf("language" to language)
                )
            )
        ) {
            is YamResult.Success -> when (
                val decoded = YamResponseDecoder.decodeResult(
                    response.value,
                    ListSerializer(RotorStationResultPayload.serializer())
                )
            ) {
                is YamResult.Success -> {
                    val source = decoded.value
                        .firstOrNull { it.station.id.value == station }
                        ?.station
                        ?.idForFrom
                    if (source.isNullOrBlank()) {
                        invalidArguments(
                            "Для станции $station отсутствует idForFrom"
                        )
                    } else {
                        YamResult.Success(source)
                    }
                }
                is YamResult.Failure -> decoded
            }
            is YamResult.Failure -> response
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun feedback(
        station: String,
        type: RotorFeedbackType,
        trackId: String? = null,
        from: String? = null,
        totalPlayedSeconds: Float? = null,
        batchId: String? = null
    ): YamResult<Unit> {
        if (
            station.isBlank() ||
            trackId?.isBlank() == true ||
            from?.isBlank() == true ||
            batchId?.isBlank() == true ||
            totalPlayedSeconds?.let { it < 0f } == true
        ) {
            return invalidArguments("Некорректные параметры feedback")
        }

        val timestamp = timestampSeconds()
        if (!timestamp.isFinite()) {
            return invalidArguments("timestamp должен быть конечным числом")
        }

        val body = buildJsonObject {
            put("type", type.apiValue)
            put(
                "timestamp",
                JsonUnquotedLiteral(timestamp.toPlainDecimal())
            )
            trackId?.let { put("trackId", it) }
            from?.let { put("from", it) }
            totalPlayedSeconds
                ?.takeIf { it != 0f }
                ?.let { put("totalPlayedSeconds", it) }
        }
        val feedbackBody = YamHttpBody.Json(body.toString())
        val firstResult = sendFeedback(station, feedbackBody, batchId)
        return if (
            batchId != null &&
            firstResult.isRejectedBatchCondition()
        ) {
            sendFeedback(station, feedbackBody, batchId = null)
        } else {
            firstResult
        }
    }

    private suspend fun sendFeedback(
        station: String,
        body: YamHttpBody.Json,
        batchId: String?
    ): YamResult<Unit> {
        return when (
            val response = transport.execute(
                YamHttpRequest(
                    method = YamHttpMethod.POST,
                    path = "/rotor/station/$station/feedback",
                    query = batchId
                        ?.let { mapOf("batch-id" to it) }
                        .orEmpty(),
                    body = body
                )
            )
        ) {
            is YamResult.Success -> when (
                val decoded = YamResponseDecoder.decodeResult(
                    response.value,
                    String.serializer()
                )
            ) {
                is YamResult.Success -> if (decoded.value == "ok") {
                    YamResult.Success(Unit)
                } else {
                    YamResult.Failure(
                        YamError.InvalidResponse(
                            IllegalArgumentException(
                                "Ожидался результат ok, получено ${decoded.value}"
                            )
                        )
                    )
                }
                is YamResult.Failure -> decoded
            }
            is YamResult.Failure -> response
        }
    }

    private fun YamResult<Unit>.isRejectedBatchCondition(): Boolean {
        val httpError = (this as? YamResult.Failure)
            ?.error as? YamError.Http
        return httpError?.statusCode == 400 &&
            httpError.code.equals(
                BATCH_CONDITION_ERROR,
                ignoreCase = true
            )
    }

    private fun invalidArguments(message: String): YamResult.Failure =
        YamResult.Failure(
            YamError.InvalidResponse(
                IllegalArgumentException(message)
            )
        )

    private fun Double.toPlainDecimal(): String =
        BigDecimal.valueOf(this)
            .stripTrailingZeros()
            .toPlainString()

    private companion object {
        const val MILLIS_IN_SECOND = 1_000.0
        const val BATCH_CONDITION_ERROR = "condition is not met"
    }
}
