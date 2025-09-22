package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.entities.toEntity
import com.yellastrodev.dwij.data.source.WaveRemoteSourse
import com.yellastrodev.yandexmusiclib.YamApiClient

class WaveRepository(
    val remote: WaveRemoteSourse,
    val trackRepository: TrackRepository
) {

    suspend fun getWave(): List<dYaTrack> {
        val result = remote.getWave()
        when(result){
            is YamApiClient.WaveResult.Success -> {

                val trackList = result.trackList.map { it.toEntity() }
                trackRepository.putTracks(trackList)
                return trackList
            }

            YamApiClient.WaveResult.Error.AccessDenied -> TODO()
            YamApiClient.WaveResult.Error.NoInternet -> TODO()
            is YamApiClient.WaveResult.Error.Unknown -> TODO()
            YamApiClient.WaveResult.Error.netError -> TODO()
        }

    }
}