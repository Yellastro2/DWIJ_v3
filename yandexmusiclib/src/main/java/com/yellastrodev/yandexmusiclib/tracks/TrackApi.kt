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

internal class TrackApi(
    private val transport: YamTransport
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
}
