package com.yellastrodev.dwij.data.entities

import androidx.room.Entity
import com.yellastrodev.yandexmusiclib.CONSTANTS.Companion.LIKED_ID
import com.yellastrodev.yandexmusiclib.entities.YaLikeTracklist


@Entity
class dYaLikeTracklist(
    playlistUuid: String,
    uid: Int,
    revision: Int,
    trackCount: Int,
    duration: Int
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
), dTracklist {
    override fun getdId(): String = playlistUuid

    companion object {
        const val KIND_LIKED = "liked"
    }

    override fun getDTitle(): String = title

    override fun getType(): String = KIND_LIKED
    override fun getWaveId(): String = "playlist:$playlistUuid"
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
        duration = 0
    )
    entity.tracks = tracks
    return entity
}