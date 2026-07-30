package com.yellastrodev.dwij.data.source

import android.util.Log
import com.yellastrodev.dwij.data.entities.dYaWave
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.network.YamResult
import com.yellastrodev.yandexmusiclib.rotor.RotorBatch

class WaveRemoteSource(private val client: YamApiClient) {

    val TAG = "WaveRemoteSource"

    suspend fun getWave(tag: String = "user:onyourwave"): YamResult<RotorBatch> {
        Log.d(TAG, "getWave: $tag")
        return client.startWave(tag)
    }

    internal suspend fun sendTrackStarted(
        wave: dYaWave,
        trackId: String,
    ): YamResult<Unit> {
        val result = client.sendWaveTrackStarted(
            station = wave.radioSessionId,
            trackId = trackId,
            batchId = wave.batchId
        )
        logFeedback("sendTrackStarted", "trackId=$trackId", result)
        return result
    }

    suspend fun sendTrackSkip(
        wave: dYaWave,
        trackId: String,
        position: Int
    ): YamResult<Unit> {
        val result = client.sendWaveTrackSkipped(
            station = wave.radioSessionId,
            trackId = trackId,
            totalPlayedSeconds = position.toFloat(),
            batchId = wave.batchId
        )
        logFeedback("sendTrackSkip", "trackId=$trackId, position=$position", result)
        return result
    }

    suspend fun sendTrackFinished(
        wave: dYaWave,
        trackId: String,
        position: Int
    ): YamResult<Unit> {
        val result = client.sendWaveTrackFinished(
            station = wave.radioSessionId,
            trackId = trackId,
            totalPlayedSeconds = position.toFloat(),
            batchId = wave.batchId
        )
        logFeedback(
            "sendTrackFinished",
            "trackId=$trackId, position=$position",
            result
        )
        return result
    }

    suspend fun sendWaveStarted(wave: dYaWave): YamResult<Unit> {
        val result = client.sendWaveStarted(
            station = wave.radioSessionId,
            batchId = wave.batchId
        )
        logFeedback("sendWaveStarted", "station=${wave.radioSessionId}", result)
        return result
    }

    suspend fun getNextTracks(
        wave: dYaWave,
        previousTrackId: String
    ): YamResult<RotorBatch> {
        return client.nextWaveTracks(
            station = wave.radioSessionId,
            previousTrackId = previousTrackId
        )
    }

    private fun logFeedback(
        function: String,
        context: String,
        result: YamResult<Unit>
    ) {
        when (result) {
            is YamResult.Success -> Log.d(
                TAG,
                "[$function] Фидбек успешно отправлен: $context"
            )
            is YamResult.Failure -> Log.e(
                TAG,
                "[$function] Фидбек не отправлен: $context, error=${result.error}"
            )
        }
    }

}
