package com.yellastrodev.dwij.data.entities

import androidx.room.Entity
import com.yellastrodev.yandexmusiclib.CONSTANTS.Companion.LIKED_ID
import com.yellastrodev.yandexmusiclib.entities.YaLikeTracklist
import com.yellastrodev.yandexmusiclib.entities.YaPlaylist


@Entity
class dYaLikeTracklist(
    playlistUuid: String,
    uid: Int,
    revision: Int,
    trackCount: Int,
    duration: Int,
    tracks: List<dPlaylistTrack>
) : dYaPlaylist(
    playlistUuid,
    uid = uid,
    kind = LIKED_ID,
    title = "liked tracks",
    trackCount = trackCount,
    durationMs = duration,
    revision = revision,
    snapshot = 0,
    visibility = "false",
    collective = false,
    isBanner = false,
    isPremiere = false,
    ogImageUri = "",
    backgroundImageUrl = "",
    description = "",
) {
}

fun YaLikeTracklist.toEntity(): dYaLikeTracklist {
    val tracks = tracks.map { trackShort ->
        dPlaylistTrack(
            playlistUuid = playlistUuid,
            trackId = trackShort.id
        )
    }
    val entity = dYaLikeTracklist(
        playlistUuid = playlistUuid,
        uid = uid,
        trackCount = tracks.size,
        revision = revision,
        tracks = tracks,
        duration = 0
    )
    return entity
}