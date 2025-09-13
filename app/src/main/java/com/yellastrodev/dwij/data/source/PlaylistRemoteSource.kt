package com.yellastrodev.dwij.data.source

import com.yellastrodev.dwij.entities.dYaPlaylist
import com.yellastrodev.dwij.entities.dYaTrack
import com.yellastrodev.dwij.entities.toEntity
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.YamApiClient.PlaylistResult
import com.yellastrodev.yandexmusiclib.entities.YaPlaylist
import com.yellastrodev.yandexmusiclib.entities.YaTrack
import org.json.JSONObject

class PlaylistRemoteSource(private val client: YamApiClient) {
    suspend fun fetch(kind: Int): dPlaylistResult {
        val result = client.getPlaylistObj(kind)
        if (result is PlaylistResult.Success) {
            return dPlaylistResult.Success(
                result.YaPlaylist.toEntity(),
                result.trackList.map { it.toEntity() }
            )
        } else {
            return dPlaylistResult.Error.Unknown(Throwable("Unknown error"))
        }
    }
    suspend fun fetchAll(): List<dYaPlaylist> = client.getUserListPllists().map { it.toEntity() }
}

sealed class dPlaylistResult {
    data class Success(
        val YaPlaylist: dYaPlaylist,
        val trackList: List<dYaTrack>) : dPlaylistResult()
    sealed class Error : dPlaylistResult() {
        object netError : Error()
        object NoInternet : Error()
        object AccessDenied : Error()
        data class Unknown(val throwable: Throwable) : Error()
    }
}