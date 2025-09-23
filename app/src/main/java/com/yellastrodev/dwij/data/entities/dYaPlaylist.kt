package com.yellastrodev.dwij.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yellastrodev.yandexmusiclib.entities.YaPlaylist

@Entity(tableName = "playlists")
open class dYaPlaylist(
     @PrimaryKey val playlistUuid: String,
     val uid: Int,
     val kind: String,
     val title: String,
     val description: String?,
     val trackCount: Int,
     val revision: Int,
     val snapshot: Int,
     val visibility: String,
     val collective: Boolean,
     val isBanner: Boolean,
     val isPremiere: Boolean,
     val durationMs: Int?,
     val ogImageUri: String?,
     val backgroundImageUrl: String?,
//     val tracks: List<TrackShort> = emptyList(),
     // при желании можно добавить cover, coverWithoutText, playCounter и т.д.
): dTracklist{

     companion object {
          const val YA_PLAYLIST = "ya_playlist"
     }

     @Ignore
     var tracks: List<dPlaylistTrack> = emptyList()
     override fun getdId(): String = playlistUuid

     override fun getDTitle(): String = title

     override fun getType(): String = YA_PLAYLIST
     override fun getWaveId(): String = "playlist:${uid}_${kind}"
}

fun YaPlaylist.toEntity(): dYaPlaylist {
     val entity = dYaPlaylist(
          playlistUuid = playlistUuid,
          uid = uid,
          kind = kind,
          title = title,
          description = description,
          trackCount = trackCount,
          revision = revision,
          snapshot = snapshot,
          visibility = visibility,
          collective = collective,
          isBanner = isBanner,
          isPremiere = isPremiere,
          durationMs = durationMs,
          ogImageUri = ogImageUri,
          backgroundImageUrl = backgroundImageUrl
     )
     // Заполняем @Ignore-поле вручную. сохраняем порядок элементов в переменную position
     entity.tracks = tracks.map { trackShort ->
          dPlaylistTrack(
               playlistUuid = playlistUuid,
               trackId = trackShort.id,
               position = tracks.indexOf(trackShort)
          )
     }
     return entity
}

@Entity(
     tableName = "playlist_tracks",
     primaryKeys = ["playlistUuid", "trackId"],
     foreignKeys = [
          ForeignKey(
               entity = dYaPlaylist::class,
               parentColumns = ["playlistUuid"],
               childColumns = ["playlistUuid"],
               onDelete = ForeignKey.CASCADE
          )
     ],
     indices = [Index("playlistUuid")]
)
data class dPlaylistTrack(
     val playlistUuid: String,
     val trackId: String,
     val position: Int? = null
)