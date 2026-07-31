package com.yellastrodev.dwij.data.source

import android.util.Log
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.network.YamResult
import com.yellastrodev.yandexmusiclib.tracks.PlayAudioRequest

enum class PlaybackReportType(val logValue: String) {
    START("start"),
    FINISH("finish"),
    INTERRUPTED("interrupted")
}

/** Сетевой источник универсальной телеметрии `/play-audio`. */
class PlaybackRemoteSource(
    private val client: YamApiClient
) {
    suspend fun send(
        type: PlaybackReportType,
        request: PlayAudioRequest
    ): YamResult<Unit> {
        val result = client.playAudio(request)
        val context = buildString {
            append("type=${type.logValue}, trackId=${request.trackId}")
            request.playlistId?.let { append(", playlistId=$it") }
            if (type != PlaybackReportType.START) {
                append(", listened=${request.totalPlayedSeconds}с")
                append(", position=${request.endPositionSeconds}с")
            }
        }
        when (result) {
            is YamResult.Success -> Log.d(
                TAG,
                "[send] play-audio успешно отправлен: $context"
            )
            is YamResult.Failure -> Log.e(
                TAG,
                "[send] play-audio не отправлен: $context, error=${result.error}"
            )
        }
        return result
    }

    private companion object {
        const val TAG = "PlaybackRemoteSource"
    }
}
