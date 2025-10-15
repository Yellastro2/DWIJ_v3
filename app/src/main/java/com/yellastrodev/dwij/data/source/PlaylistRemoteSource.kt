package com.yellastrodev.dwij.data.source

import android.util.Log
import com.yellastrodev.dwij.data.entities.dYaLikeTracklist
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.entities.toEntity
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.YamApiClient.PlaylistResult
import com.yellastrodev.yandexmusiclib.entities.YaLikeTracklist

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
    suspend fun fetchLikelist(): dPlaylistResult {

        return dPlaylistResult.Success(client.getLikedTracklist().toEntity(), emptyList())
    }

    suspend fun addTrackToPlaylist(playlist: dYaPlaylist, track: dYaTrack) {
        Log.d("PlaylistRemoteSource", "addTrackToPlaylist: ${track.id}, albums = ${track.albums.size}")
        client.addTrack(playlist.kind.toInt(), playlist.revision, track.id, track.albums[0].id.toString())
    }

    suspend fun removeTrackFromPlaylist(playlist: dYaPlaylist, trackNumber: Int) {
        Log.d("PlaylistRemoteSource", "removeTrackFromPlaylist: plId: ${playlist.title}, trackNumber: $trackNumber")
        client.removeTrack(playlist.kind.toInt(), playlist.revision, trackNumber)
    }

    suspend fun likeTrack(trackId: String, likeOn: Boolean){
        client.likeAction(
            "track",
            trackId,
            likeOn
        )
    }
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