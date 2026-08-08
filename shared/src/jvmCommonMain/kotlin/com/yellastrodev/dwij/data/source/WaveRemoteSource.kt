package com.yellastrodev.dwij.data.source

import com.yellastrodev.dwij.data.entities.dYaWave
import com.yellastrodev.yamusicsdk.YamApiClient
import com.yellastrodev.yamusicsdk.YamLogger
import com.yellastrodev.yamusicsdk.network.YamResult
import com.yellastrodev.yamusicsdk.rotor.RotorBatch

class WaveRemoteSource(private val client: YamApiClient, val logger: YamLogger) {

    val TAG = "WaveRemoteSource"

    suspend fun getWave(tag: String = "user:onyourwave"): YamResult<RotorBatch> {
        logger.debug(TAG, "getWave: $tag")
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
        logFeedback(
            function = "sendTrackStarted",
            type = "trackStarted",
            context = "trackId=$trackId",
            result = result
        )
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
        logFeedback(
            function = "sendTrackSkip",
            type = "skip",
            context = "trackId=$trackId, position=$position",
            result = result
        )
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
            function = "sendTrackFinished",
            type = "trackFinished",
            context = "trackId=$trackId, position=$position",
            result = result
        )
        return result
    }

    suspend fun sendWaveStarted(wave: dYaWave): YamResult<Unit> {
        val result = client.sendWaveStarted(
            station = wave.radioSessionId,
            batchId = wave.batchId
        )
        logFeedback(
            function = "sendWaveStarted",
            type = "radioStarted",
            context = "station=${wave.radioSessionId}",
            result = result
        )
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
        type: String,
        context: String,
        result: YamResult<Unit>
    ) {
        when (result) {
            is YamResult.Success -> logger.debug(
                TAG,
                "[$function] Фидбек type=$type успешно отправлен: $context"
            )
            is YamResult.Failure -> logger.error(
                TAG,
                "[$function] Фидбек type=$type не отправлен: " +
                    "$context, error=${result.error}"
            )
        }
    }

}