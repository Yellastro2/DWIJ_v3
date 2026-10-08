package com.yellastrodev.dwij.data.entities

import androidx.room.Entity
import com.yellastrodev.yamusicsdk.entities.YaLikeTracklist


/** Серверный список лайков ЯМ с rotor seed по владельцу и kind, а не Room UUID. */
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
    kind = KIND_LIKED,
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
    /** Использует серверную идентичность плейлиста вместо внутреннего UUID Room. */
    override fun getWaveId(): String = super<dYaPlaylist>.getWaveId()
}

fun YaLikeTracklist.toEntity(): dYaLikeTracklist {
    val tracks = tracks.mapIndexed { position, trackShort ->
        dPlaylistTrack(
            playlistUuid = playlistUuid,
            trackId = trackShort.id,
            position = position
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
