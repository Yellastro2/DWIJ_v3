package com.yellastrodev.dwij.data.source

import com.yellastrodev.dwij.playback.feedback.PlaybackReportType
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.YamLogger
import com.yellastrodev.yandexmusiclib.network.YamResult
import com.yellastrodev.yandexmusiclib.tracks.PlayAudioRequest

/** Сетевой источник универсальной телеметрии `/play-audio`. */
class PlaybackRemoteSource(
    private val client: YamApiClient,
    private val logger: YamLogger,
) {
    suspend fun send(
        type: PlaybackReportType,
        request: PlayAudioRequest,
    ): YamResult<Unit> {
        val result = client.playAudio(request)

        val context = buildString {
            append("type=${type.logValue}, trackId=${request.trackId}")
            request.playlistId?.let { playlistId ->
                append(", playlistId=$playlistId")
            }
            if (type != PlaybackReportType.START) {
                append(", listened=${request.totalPlayedSeconds}с")
                append(", position=${request.endPositionSeconds}с")
            }
        }

        when (result) {
            is YamResult.Success -> {
                logger.debug(
                    TAG,
                    "[send] play-audio успешно отправлен: $context",
                )
            }
            is YamResult.Failure -> {
                logger.error(
                    TAG,
                    "[send] play-audio не отправлен: " +
                        "$context, error=${result.error}",
                    null,
                )
            }
        }

        return result
    }

    private companion object {
        const val TAG = "PlaybackRemoteSource"
    }
}
