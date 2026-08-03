package com.yellastrodev.yandexmusiclib.likes

import com.yellastrodev.yandexmusiclib.entities.YaLikeTracklist
import com.yellastrodev.yandexmusiclib.network.YamError
import com.yellastrodev.yandexmusiclib.network.YamHttpBody
import com.yellastrodev.yandexmusiclib.network.YamHttpMethod
import com.yellastrodev.yandexmusiclib.network.YamHttpRequest
import com.yellastrodev.yandexmusiclib.network.YamResponseDecoder
import com.yellastrodev.yandexmusiclib.network.YamResult
import com.yellastrodev.yandexmusiclib.network.YamTransport

internal class LikesApi(
    private val transport: YamTransport
) {
    suspend fun likedTracks(
        userId: String,
        ifModifiedSinceRevision: Int = 0
    ): YamResult<YaLikeTracklist> {
        if (userId.isBlank() || ifModifiedSinceRevision < 0) {
            return invalidArguments(
                "userId не должен быть пустым, а ревизия — отрицательной"
            )
        }

        return when (
            val response = transport.execute(
                YamHttpRequest(
                    method = YamHttpMethod.GET,
                    path = "/users/$userId/likes/tracks",
                    query = mapOf(
                        "if-modified-since-revision" to
                            ifModifiedSinceRevision.toString()
                    )
                )
            )
        ) {
            is YamResult.Success -> when (
                val decoded = YamResponseDecoder.decodeResult(
                    response = response.value,
                    resultSerializer = LikedTracksResult.serializer()
                )
            ) {
                is YamResult.Success -> YamResult.Success(decoded.value.library)
                is YamResult.Failure -> decoded
            }
            is YamResult.Failure -> response
        }
    }

    suspend fun setTrackLiked(
        userId: String,
        trackId: String,
        liked: Boolean
    ): YamResult<LikeActionResult> {
        if (userId.isBlank() || trackId.isBlank()) {
            return invalidArguments("userId и trackId не должны быть пустыми")
        }

        val action = if (liked) "add-multiple" else "remove"
        return when (
            val response = transport.execute(
                YamHttpRequest(
                    method = YamHttpMethod.POST,
                    path = "/users/$userId/likes/tracks/$action",
                    body = YamHttpBody.Form(
                        mapOf("track-ids" to trackId)
                    )
                )
            )
        ) {
            is YamResult.Success -> YamResponseDecoder.decodeResult(
                response = response.value,
                resultSerializer = LikeActionResult.serializer()
            )
            is YamResult.Failure -> response
        }
    }

    private fun invalidArguments(message: String): YamResult.Failure =
        YamResult.Failure(
            YamError.InvalidResponse(
                IllegalArgumentException(message)
            )
        )
}
