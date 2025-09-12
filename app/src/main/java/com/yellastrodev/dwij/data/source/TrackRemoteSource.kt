package com.yellastrodev.dwij.data.source

import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.YamApiClient.PlaylistResult
import com.yellastrodev.yandexmusiclib.entities.YaTrack
import com.yellastrodev.yandexmusiclib.kot_utils.yNetwork.Companion.NetResult
import com.yellastrodev.yandexmusiclib.kot_utils.yTrack.Companion.Mp3LinkResult

class TrackRemoteSource(private val client: YamApiClient) {
    suspend fun fetch(track: YaTrack): Mp3LinkResult = track.mp3Link(client)
}