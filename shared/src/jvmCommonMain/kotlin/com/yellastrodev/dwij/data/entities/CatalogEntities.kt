package com.yellastrodev.dwij.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Канонический артист, не привязанный идентичностью к конкретному каталогу. */
@Entity(tableName = "catalog_artists", primaryKeys = ["artistId"])
data class CatalogArtistEntity(
    val artistId: String,
    val name: String,
)

/** Метаданные артиста, полученные от явно указанного внешнего источника. */
@Entity(
    tableName = "catalog_artist_metadata",
    primaryKeys = ["artistId", "source"],
    foreignKeys = [
        ForeignKey(
            entity = CatalogArtistEntity::class,
            parentColumns = ["artistId"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("artistId"),
        Index(value = ["source", "externalId"], unique = true),
    ],
)
data class CatalogArtistMetadataEntity(
    val artistId: String,
    val source: String,
    val externalId: String,
    val coverUri: String?,
    val genres: String,
    val likesCount: Long?,
    val trackCount: Int?,
    val lastMonthListeners: Long?,
    val lastMonthListenersDelta: Long?,
    val refreshedAt: Long,
)

/** Связь артиста каталога с конкретным source-инстансом песни. */
@Entity(
    tableName = "track_instance_artists",
    primaryKeys = ["instanceId", "artistId"],
    foreignKeys = [
        ForeignKey(
            entity = TrackInstanceEntity::class,
            parentColumns = ["instanceId"],
            childColumns = ["instanceId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CatalogArtistEntity::class,
            parentColumns = ["artistId"],
            childColumns = ["artistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("instanceId"), Index("artistId")],
)
data class TrackInstanceArtistEntity(
    val instanceId: String,
    val artistId: String,
    val position: Int,
)

/** Связь альбома каталога с конкретным source-инстансом песни. */
@Entity(
    tableName = "track_instance_albums",
    primaryKeys = ["instanceId", "albumId"],
    foreignKeys = [
        ForeignKey(
            entity = TrackInstanceEntity::class,
            parentColumns = ["instanceId"],
            childColumns = ["instanceId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CatalogAlbumEntity::class,
            parentColumns = ["albumId"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("instanceId"), Index("albumId")],
)
data class TrackInstanceAlbumEntity(
    val instanceId: String,
    val albumId: String,
    val position: Int,
)

/** Канонический альбом, который позднее может получить несколько source-привязок. */
@Entity(tableName = "catalog_albums", primaryKeys = ["albumId"])
data class CatalogAlbumEntity(
    val albumId: String,
    val title: String,
)

/** Метаданные альбома, полученные от явно указанного внешнего источника. */
@Entity(
    tableName = "catalog_album_metadata",
    primaryKeys = ["albumId", "source"],
    foreignKeys = [
        ForeignKey(
            entity = CatalogAlbumEntity::class,
            parentColumns = ["albumId"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("albumId"),
        Index(value = ["source", "externalId"], unique = true),
    ],
)
data class CatalogAlbumMetadataEntity(
    val albumId: String,
    val source: String,
    val externalId: String,
    val coverUri: String?,
    val artistNames: String,
    val genre: String?,
    val releaseDate: String?,
    val year: Int?,
    val type: String?,
    val description: String?,
    val likesCount: Long?,
    val trackCount: Int?,
    val refreshedAt: Long,
)

/** Сохранённый порядок source-треков внутри альбома. */
@Entity(
    tableName = "catalog_album_tracks",
    primaryKeys = ["albumId", "source", "position"],
    foreignKeys = [
        ForeignKey(
            entity = CatalogAlbumEntity::class,
            parentColumns = ["albumId"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("albumId"), Index("sourceTrackId")],
)
data class CatalogAlbumTrackEntity(
    val albumId: String,
    val source: String,
    val position: Int,
    val sourceTrackId: String,
    val discNumber: Int?,
    val trackNumber: Int?,
)

const val CATALOG_SOURCE_YANDEX = "YANDEX"
const val CATALOG_VALUE_SEPARATOR = "\u001F"
