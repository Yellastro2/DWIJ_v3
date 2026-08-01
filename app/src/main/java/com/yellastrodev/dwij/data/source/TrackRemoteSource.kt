package com.yellastrodev.dwij.data.source

import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.entities.toEntity
import com.yellastrodev.dwij.data.toDataError
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.network.YamResult

class TrackRemoteSource(private val client: YamApiClient) {
    suspend fun fetch(track: dYaTrack): DataResult<ByteArray> =
        when (val result = client.trackDownloadBytes(track.id)) {
            is YamResult.Success -> DataResult.Success(result.value)
            is YamResult.Failure -> DataResult.Failure(result.error.toDataError())
        }

    suspend fun fetchTracks(
        trackIds: List<String>
    ): DataResult<List<dYaTrack>> {
        return when (val result = client.tracks(trackIds)) {
            is YamResult.Success -> DataResult.Success(
                result.value.map { it.toEntity() }
            )
            is YamResult.Failure -> DataResult.Failure(
                result.error.toDataError()
            )
        }
    }
}
