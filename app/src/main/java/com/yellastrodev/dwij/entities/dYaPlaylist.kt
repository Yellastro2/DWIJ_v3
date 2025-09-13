package com.yellastrodev.dwij.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yellastrodev.yandexmusiclib.entities.TrackShort
import com.yellastrodev.yandexmusiclib.entities.YaPlaylist

@Entity(tableName = "playlists")
data class dYaPlaylist(
     @PrimaryKey val playlistUuid: String,
     val uid: Int,
     val kind: Int,
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
){

     @Ignore
     var tracks: List<dPlaylistTrack> = emptyList()
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
          backgroundImageUrl = backgroundImageUrl,
//          tracks = tracks
     )
     // Заполняем @Ignore-поле вручную
     entity.tracks = tracks.map { trackShort ->
          dPlaylistTrack(
               playlistUuid = playlistUuid,
               trackId = trackShort.id
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
     val trackId: String
)