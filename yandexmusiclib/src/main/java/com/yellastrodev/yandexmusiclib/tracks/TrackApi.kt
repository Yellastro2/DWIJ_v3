package com.yellastrodev.yandexmusiclib.tracks

import com.yellastrodev.yandexmusiclib.entities.YaTrack
import com.yellastrodev.yandexmusiclib.network.YamError
import com.yellastrodev.yandexmusiclib.network.YamHttpBody
import com.yellastrodev.yandexmusiclib.network.YamHttpMethod
import com.yellastrodev.yandexmusiclib.network.YamHttpRequest
import com.yellastrodev.yandexmusiclib.network.YamResponseDecoder
import com.yellastrodev.yandexmusiclib.network.YamResult
import com.yellastrodev.yandexmusiclib.network.YamTransport
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.math.BigDecimal
import java.time.Instant

internal class TrackApi(
    private val transport: YamTransport,
    private val currentIsoTimestamp: () -> String = {
        Instant.now().toString()
    }
) {
    suspend fun tracks(
        trackIds: List<String>,
        withPositions: Boolean = true
    ): YamResult<List<YaTrack>> {
        if (trackIds.isEmpty() || trackIds.any { it.isBlank() }) {
            return YamResult.Failure(
                YamError.InvalidResponse(
                    IllegalArgumentException("trackIds не должен быть пустым")
                )
            )
        }

        return when (
            val response = transport.execute(
                YamHttpRequest(
                    method = YamHttpMethod.POST,
                    path = "/tracks",
                    body = YamHttpBody.Form(
                        mapOf(
                            "track-ids" to trackIds.joinToString(","),
                            "with-positions" to if (withPositions) "True" else "False"
                        )
                    )
                )
            )
        ) {
            is YamResult.Success -> YamResponseDecoder.decodeResult(
                response.value,
                ListSerializer(YaTrack.serializer())
            )
            is YamResult.Failure -> response
        }
    }

    /** Отправляет состояние обычного воспроизведения по контракту Python SDK. */
    suspend fun playAudio(request: PlayAudioRequest): YamResult<Unit> {
        validatePlayAudioRequest(request)?.let { return it }

        val timestamp = request.timestamp ?: currentIsoTimestamp()
        val clientNow = request.clientNow ?: currentIsoTimestamp()
        val fields = buildMap {
            put("track-id", request.trackId)
            put("from-cache", if (request.fromCache) "True" else "False")
            put("from", request.source)
            put("play-id", request.playId)
            request.uid?.let { put("uid", it.toString()) }
            put("timestamp", timestamp)
            put("track-length-seconds", request.trackLengthSeconds.toString())
            put("total-played-seconds", request.totalPlayedSeconds.toPlainDecimal())
            put("end-position-seconds", request.endPositionSeconds.toPlainDecimal())
            put("album-id", request.albumId)
            request.playlistId?.let { put("playlist-id", it) }
            put("client-now", clientNow)
        }

        return when (
            val response = transport.execute(
                YamHttpRequest(
                    method = YamHttpMethod.POST,
                    path = "/play-audio",
                    body = YamHttpBody.Form(fields)
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
                    invalidArguments(
                        "Ожидался результат ok, получено ${decoded.value}"
                    )
                }
                is YamResult.Failure -> decoded
            }
            is YamResult.Failure -> response
        }
    }

    private fun validatePlayAudioRequest(
        request: PlayAudioRequest
    ): YamResult.Failure? {
        val hasBlankRequiredField = request.trackId.isBlank() ||
            request.source.isBlank() ||
            request.albumId.isBlank() ||
            request.playId.isBlank()
        val hasBlankOptionalField = request.playlistId?.isBlank() == true ||
            request.timestamp?.isBlank() == true ||
            request.clientNow?.isBlank() == true
        val hasInvalidDuration = request.trackLengthSeconds < 0 ||
            !request.totalPlayedSeconds.isFinite() ||
            request.totalPlayedSeconds < 0.0 ||
            !request.endPositionSeconds.isFinite() ||
            request.endPositionSeconds < 0.0

        return if (
            hasBlankRequiredField ||
            hasBlankOptionalField ||
            hasInvalidDuration
        ) {
            invalidArguments("Некорректные параметры play-audio")
        } else {
            null
        }
    }

    private fun Double.toPlainDecimal(): String =
        BigDecimal.valueOf(this)
            .stripTrailingZeros()
            .toPlainString()

    private fun invalidArguments(message: String): YamResult.Failure =
        YamResult.Failure(
            YamError.InvalidResponse(
                IllegalArgumentException(message)
            )
        )
}
