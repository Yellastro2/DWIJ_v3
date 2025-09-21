package com.yellastrodev.dwij.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.YamApiClient.Companion.BASE_URL
import com.yellastrodev.yandexmusiclib.entities.YaTrack
import com.yellastrodev.yandexmusiclib.kot_utils.yNetwork
import com.yellastrodev.yandexmusiclib.kot_utils.yNetwork.Companion.NetResult
import com.yellastrodev.yandexmusiclib.kot_utils.yTrack.Companion.Mp3LinkResult
import com.yellastrodev.yandexmusiclib.kot_utils.yTrack.Companion.getMd5
import com.yellastrodev.yandexmusiclib.kot_utils.yTrack.Companion.get_XML_Field

@Entity(tableName = "tracks")
class dYaTrack(
    @PrimaryKey
    val id: String,
    val title: String,
    val available: Boolean,
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

    suspend fun mp3Link(fClient: YamApiClient): Mp3LinkResult {
        val urlToRequest = "/tracks/$id/download-info"

        val result = yNetwork.getRepeatWithHeader(fClient.mToken, BASE_URL + urlToRequest)

        if (result is NetResult.Success) {
            val resultGetXml = yNetwork.getXml(
                fClient.mToken,
                result.json.getJSONArray("result").getJSONObject(0)
                    .getString("downloadInfoUrl")
            )

            // Generating mp3 link
            val host = get_XML_Field(resultGetXml, "host")
            val path = get_XML_Field(resultGetXml, "path")
            val ts = get_XML_Field(resultGetXml, "ts")
            val s = get_XML_Field(resultGetXml, "s")
            val secret =
                String.format("XGRlBW9FXlekgbPrRHuSiA%s%s", path.substring(1, path.length - 1), s)
            val sign = getMd5(secret)

            return Mp3LinkResult.Success(String.format(
                "https://%s/get-%s/%s/%s/%s",
                host,
                "mp3",
                sign,
                ts,
                path
            ))
        } else
            return Mp3LinkResult.Error(result as NetResult.Error)
    }
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

fun YaTrack.toEntity(): dYaTrack =
    dYaTrack(
        id = id,
        title = title,
        available = available,
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
