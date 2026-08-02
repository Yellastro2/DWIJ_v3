package com.yellastrodev.dwij.data.source

import android.util.Log
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.dYaLikeTracklist
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.entities.toEntity
import com.yellastrodev.dwij.data.toDataError
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.network.YamResult

class PlaylistRemoteSource(private val client: YamApiClient) {
    /** Создаёт пустой плейлист пользователя и переводит сетевую модель в app-entity. */
    suspend fun createPlaylist(
        title: String,
        isPublic: Boolean,
    ): DataResult<dYaPlaylist> = when (
        val result = client.createPlaylist(
            title = title,
            isPublic = isPublic,
        )
    ) {
        is YamResult.Success -> DataResult.Success(result.value.toEntity())
        is YamResult.Failure -> DataResult.Failure(result.error.toDataError())
    }

    suspend fun fetch(kind: Int): DataResult<PlaylistSnapshot> {
        return when (val result = client.playlist(kind)) {
            is YamResult.Success -> DataResult.Success(
                PlaylistSnapshot(
                    playlist = result.value.playlist.toEntity(),
                    tracks = result.value.tracks.map { it.toEntity() }
                )
            )
            is YamResult.Failure -> DataResult.Failure(
                result.error.toDataError()
            )
        }
    }

    suspend fun fetchAll(): DataResult<List<dYaPlaylist>> =
        when (val result = client.playlists()) {
            is YamResult.Success -> DataResult.Success(
                result.value.map { it.toEntity() }
            )
            is YamResult.Failure -> DataResult.Failure(
                result.error.toDataError()
            )
        }

    suspend fun fetchLikelist(): DataResult<dYaLikeTracklist> =
        when (val result = client.likedTracks()) {
            is YamResult.Success -> DataResult.Success(result.value.toEntity())
            is YamResult.Failure -> DataResult.Failure(
                result.error.toDataError()
            )
        }

    suspend fun addTrackToPlaylist(
        playlist: dYaPlaylist,
        track: dYaTrack
    ): DataResult<dYaPlaylist> {
        val playlistKind = playlist.kind.toIntOrNull()
            ?: return DataResult.Failure(
                DataError.InvalidData("Некорректный kind=${playlist.kind}")
            )
        val albumId = track.albums.firstOrNull()?.id
            ?: return DataResult.Failure(
                DataError.InvalidData(
                    "У трека ${track.id} отсутствует albumId"
                )
            )
        Log.d(
            TAG,
            "[addTrackToPlaylist] trackId=${track.id}, albums=${track.albums.size}"
        )
        return when (
            val result = client.addTrack(
                playlistKind = playlistKind,
                revision = playlist.revision,
                trackId = track.id,
                trackAlbum = albumId.toString()
            )
        ) {
            is YamResult.Success -> DataResult.Success(result.value.toEntity())
            is YamResult.Failure -> DataResult.Failure(
                result.error.toDataError()
            )
        }
    }

    suspend fun removeTrackFromPlaylist(
        playlist: dYaPlaylist,
        trackNumber: Int
    ): DataResult<dYaPlaylist> {
        val playlistKind = playlist.kind.toIntOrNull()
            ?: return DataResult.Failure(
                DataError.InvalidData("Некорректный kind=${playlist.kind}")
            )
        Log.d(
            TAG,
            "[removeTrackFromPlaylist] playlist=${playlist.title}, position=$trackNumber"
        )
        return when (
            val result = client.removeTrack(
                playlistKind = playlistKind,
                revision = playlist.revision,
                trackNumber = trackNumber
            )
        ) {
            is YamResult.Success -> DataResult.Success(result.value.toEntity())
            is YamResult.Failure -> DataResult.Failure(
                result.error.toDataError()
            )
        }
    }

    suspend fun setTrackLiked(
        trackId: String,
        liked: Boolean
    ): DataResult<Unit> {
        return when (val result = client.setTrackLiked(
            trackId = trackId,
            liked = liked
        )) {
            is YamResult.Success -> DataResult.Success(Unit)
            is YamResult.Failure -> DataResult.Failure(
                result.error.toDataError()
            )
        }
    }

    private companion object {
        const val TAG = "PlaylistRemoteSource"
    }
}

data class PlaylistSnapshot(
    val playlist: dYaPlaylist,
    val tracks: List<dYaTrack>
)
