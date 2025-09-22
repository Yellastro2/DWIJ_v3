package com.yellastrodev.dwij.data.source

import android.util.Log
import com.yellastrodev.dwij.data.entities.dYaWave
import com.yellastrodev.yandexmusiclib.YamApiClient

class WaveRemoteSourse(private val client: YamApiClient) {

    val TAG = "WaveRemoteSourse"

    suspend fun getWave(tag: String = "user:onyourwave"): YamApiClient.WaveResult {
        Log.d(TAG, "getWave: $tag")
        val result = client.getWavetObj(tag)
//        Log.d(TAG, "getWave: $result")
        return result
    }

    internal suspend fun sendTrackStarted(
        wave: dYaWave,
        trackId: String,
        ) {
        client.rotorStationFBTrackStarted(
            wave.radioSessionId,
            trackId,
            wave.batchId
        )
    }

    suspend fun sendTrackSkip(wave: dYaWave, trackId: String, position: Int) {
        client.rotorStationFBSkip(
            wave.radioSessionId,
            trackId,
            position.toFloat(),
            wave.batchId
        )
    }

    suspend fun sendTrackFinished(wave: dYaWave, trackId: String, position: Int) {
        client.rotorStationFBTrackFinished(
            wave.radioSessionId,
            trackId,
            position.toFloat(),
            wave.batchId
        )
    }

    suspend fun sendWaveStarted(wave: dYaWave, trackId: String) {
        client.rotorStationFBRadioStarted(
            wave.radioSessionId,
            trackId,
            wave.batchId
        )
    }

    suspend fun getNextTracks(wave: dYaWave, previousTrackId: String): YamApiClient.NextWaveResult {
        val result = client.getWaveNextTracksObject(
            wave.radioSessionId,
            previousTrackId
        )
        return result
    }

}