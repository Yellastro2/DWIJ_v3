package com.yellastrodev.dwij.data.source

import android.util.Log
import com.yellastrodev.dwij.data.entities.dYaLikeTracklist
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.entities.toEntity
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.likes.LikeActionResult
import com.yellastrodev.yandexmusiclib.network.YamResult

class PlaylistRemoteSource(private val client: YamApiClient) {
    suspend fun fetch(kind: Int): dPlaylistResult {
        return when (val result = client.playlist(kind)) {
            is YamResult.Success -> dPlaylistResult.Success(
                result.value.playlist.toEntity(),
                result.value.tracks.map { it.toEntity() }
            )
            is YamResult.Failure -> dPlaylistResult.Error.Api(result.error)
        }
    }
    suspend fun fetchAll(): YamResult<List<dYaPlaylist>> =
        when (val result = client.playlists()) {
            is YamResult.Success -> YamResult.Success(
                result.value.map { it.toEntity() }
            )
            is YamResult.Failure -> result
        }
    suspend fun fetchLikelist(): YamResult<dYaLikeTracklist> =
        when (val result = client.likedTracks()) {
            is YamResult.Success -> YamResult.Success(result.value.toEntity())
            is YamResult.Failure -> result
        }

    suspend fun addTrackToPlaylist(playlist: dYaPlaylist, track: dYaTrack) {
        Log.d("PlaylistRemoteSource", "addTrackToPlaylist: ${track.id}, albums = ${track.albums.size}")
        client.addTrack(playlist.kind.toInt(), playlist.revision, track.id, track.albums[0].id.toString())
    }

    suspend fun removeTrackFromPlaylist(playlist: dYaPlaylist, trackNumber: Int) {
        Log.d("PlaylistRemoteSource", "removeTrackFromPlaylist: plId: ${playlist.title}, trackNumber: $trackNumber")
        client.removeTrack(playlist.kind.toInt(), playlist.revision, trackNumber)
    }

    suspend fun setTrackLiked(
        trackId: String,
        liked: Boolean
    ): YamResult<LikeActionResult> {
        return client.setTrackLiked(
            trackId = trackId,
            liked = liked
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
        data class Api(val error: com.yellastrodev.yandexmusiclib.network.YamError) : Error()
        data class Unknown(val throwable: Throwable) : Error()
    }
}
