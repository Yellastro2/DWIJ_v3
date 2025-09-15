package com.yellastrodev.dwij.data.source

import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.entities.toEntity
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.kot_utils.yTrack.Companion.Mp3LinkResult

class TrackRemoteSource(private val client: YamApiClient) {
    suspend fun fetch(track: dYaTrack): Mp3LinkResult = track.mp3Link(client)
//    suspend fun fetchTrack(trackId: String): YaTrack = client.getT(trackId)
    suspend fun fetchTracks(trackIds: List<String>): List<dYaTrack> = client.getTracklist(trackIds).map {
        it.toEntity()
    }
}