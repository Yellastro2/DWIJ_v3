package com.yellastrodev.dwij.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yellastrodev.yamusicsdk.entities.YaTrack
import androidx.room.Ignore

/** Сохранённые метаданные трека Яндекс Музыки и время проверки его доступности. */
@Entity(tableName = "tracks")
class dYaTrack(
    @PrimaryKey
    val id: String,
    val title: String,
    val available: Boolean,
    val availabilityCheckedAt: Long = 0L,
    val ogImageUri: String? = null,
    val coverUri: String? = null,
    val durationMs: Int? = null,
    val previewDurationMs: Int? = null,
    val storageDir: String? = null,
    val fileSize: Int? = null,
    val trackSource: String? = null,
//    val availableForPremiumUsers: Boolean? = null,
//    val availableFullWithoutPermission: Boolean? = null,
//    val availableForOptions: List<Options> = listOf(),
//    val r128: R128? = null,
//    val major: Major? = null,
//    val lyricsAvailable: Boolean? = null,
//    val lyricsInfo: LyricsInfo? = null,
//    val derivedColors: DerivedColors? = null,
//    val type: AlbumType? = null,
//    val rememberPosition: Boolean? = null,
//    val trackSharingFlag: TrackSharingFlag? = null,
//    val contentWarning: String? = null
) {

    fun getCoverUriAny() = ogImageUri ?: coverUri

    @Ignore
    var artists: List<dYaArtist> = emptyList()
    @Ignore
    var albums: List<dYaAlbum> = emptyList()

    @Ignore
    var playlists: List<String> = emptyList()

}

@Entity(tableName = "albums")
data class dYaAlbum(
    @PrimaryKey val id: Int,
    val title: String
)

@Entity(
    tableName = "track_albums",
    primaryKeys = ["trackId", "albumId"],
    foreignKeys = [
        ForeignKey(
            entity = dYaTrack::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = dYaAlbum::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("trackId"), Index("albumId")]
)
data class dTrackAlbumCrossRef(
    val trackId: String,
    val albumId: Int
)

@Entity(tableName = "artists")
data class dYaArtist(
    @PrimaryKey(autoGenerate = true)
    val localId: Long = 0,
     val id: Int? = null,
     val name: String
)

@Entity(
    tableName = "track_artists",
    primaryKeys = ["trackId", "artistLocalId"],
    foreignKeys = [
        ForeignKey(
            entity = dYaTrack::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = dYaArtist::class,
            parentColumns = ["localId"], // <-- меняем на localId
            childColumns = ["artistLocalId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("trackId"), Index("artistLocalId")]
)
data class dTrackArtistCrossRef(
    val trackId: String,
    val artistLocalId: Long
)

/** Преобразует сетевой трек и отмечает момент получения актуальной доступности. */
fun YaTrack.toEntity(availabilityCheckedAt: Long = System.currentTimeMillis()): dYaTrack =
    dYaTrack(
        id = id,
        title = title,
        available = available,
        availabilityCheckedAt = availabilityCheckedAt,
        durationMs = durationMs,
        previewDurationMs = previewDurationMs,
        storageDir = storageDir,
        fileSize = fileSize,
        trackSource = trackSource,
        ogImageUri = ogImageUri,
        coverUri = coverUri
    ).apply {
        this.artists = this@toEntity.artists.map { dYaArtist(id=it.id, name=it.name) }
        this.albums = this@toEntity.albums.map { dYaAlbum(it.id, it.title) }
    }
