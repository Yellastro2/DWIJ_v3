package com.yellastrodev.dwij.data.source

import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.entities.toEntity
import com.yellastrodev.dwij.data.toDataError
import com.yellastrodev.yamusicsdk.YamApiClient
import com.yellastrodev.yamusicsdk.network.YamResult
import java.io.OutputStream

class TrackRemoteSource(private val client: YamApiClient) {
    suspend fun fetch(track: dYaTrack): DataResult<ByteArray> =
        when (val result = client.trackDownloadBytes(track.id)) {
            is YamResult.Success -> DataResult.Success(result.value)
            is YamResult.Failure -> DataResult.Failure(result.error.toDataError())
        }

    /** Потоково сохраняет трек и передаёт наверх фактический сетевой прогресс. */
    suspend fun fetchTo(
        track: dYaTrack,
        output: OutputStream,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): DataResult<Long> =
        when (
            val result = client.trackDownloadTo(
                trackId = track.id,
                output = output,
                onProgress = onProgress,
            )
        ) {
            is YamResult.Success -> DataResult.Success(result.value)
            is YamResult.Failure -> DataResult.Failure(result.error.toDataError())
        }

    /** Загружает актуальные метаданные и ставит всей ответившей пачке одну отметку проверки. */
    suspend fun fetchTracks(
        trackIds: List<String>
    ): DataResult<List<dYaTrack>> {
        return when (val result = client.tracks(trackIds)) {
            is YamResult.Success -> {
                val availabilityCheckedAt = System.currentTimeMillis()
                DataResult.Success(
                    result.value.map { it.toEntity(availabilityCheckedAt) }
                )
            }
            is YamResult.Failure -> DataResult.Failure(
                result.error.toDataError()
            )
        }
    }
}
