package com.yellastrodev.dwij.data.source

import android.util.Log
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.entities.toEntity
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.network.YamResult

class TrackRemoteSource(private val client: YamApiClient) {
    suspend fun fetch(track: dYaTrack): YamResult<ByteArray> =
        client.trackDownloadBytes(track.id)
//    suspend fun fetchTrack(trackId: String): YaTrack = client.getT(trackId)
    suspend fun fetchTracks(trackIds: List<String>): List<dYaTrack> {
        return when (val result = client.tracks(trackIds)) {
            is YamResult.Success -> result.value.map { it.toEntity() }
            is YamResult.Failure -> {
                Log.e(TAG, "[fetchTracks] Треки не загружены: ${result.error}")
                emptyList()
            }
        }
    }

    private companion object {
        const val TAG = "TrackRemoteSource"
    }
}
