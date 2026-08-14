package com.yellastrodev.dwij.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.yellastrodev.dwij.data.entities.CatalogAlbumEntity
import com.yellastrodev.dwij.data.entities.CatalogAlbumMetadataEntity
import com.yellastrodev.dwij.data.entities.CatalogAlbumTrackEntity
import com.yellastrodev.dwij.data.entities.CatalogArtistEntity
import com.yellastrodev.dwij.data.entities.CatalogArtistMetadataEntity
import com.yellastrodev.dwij.data.entities.CATALOG_SOURCE_YANDEX
import com.yellastrodev.dwij.data.entities.TrackInstanceArtistEntity
import com.yellastrodev.dwij.data.entities.TrackInstanceAlbumEntity
import com.yellastrodev.dwij.data.entities.dYaAlbum
import com.yellastrodev.dwij.data.entities.dYaArtist
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** Внешняя source-ссылка артиста, подтверждённо связанного с логической песней. */
data class CatalogArtistSourceRef(
    val externalId: String,
    val name: String,
)

/** Room-доступ к canonical-объектам каталога и их source-метаданным. */
@Dao
abstract class CatalogDao {
    @Upsert
    abstract suspend fun upsertArtist(artist: CatalogArtistEntity)

    @Upsert
    abstract suspend fun upsertArtistMetadata(metadata: CatalogArtistMetadataEntity)

    @Query("SELECT * FROM catalog_artists WHERE artistId = :artistId LIMIT 1")
    abstract suspend fun getArtist(artistId: String): CatalogArtistEntity?

    @Query(
        "SELECT * FROM catalog_artist_metadata " +
            "WHERE artistId = :artistId AND source = :source LIMIT 1",
    )
    abstract suspend fun getArtistMetadata(
        artistId: String,
        source: String,
    ): CatalogArtistMetadataEntity?

    @Query(
        "SELECT * FROM catalog_artist_metadata " +
            "WHERE source = :source AND externalId = :externalId LIMIT 1",
    )
    abstract suspend fun getArtistMetadataByExternalId(
        source: String,
        externalId: String,
    ): CatalogArtistMetadataEntity?

    @Query(
        "SELECT catalog_artists.* FROM catalog_artists " +
            "INNER JOIN track_instance_artists links " +
            "ON links.artistId = catalog_artists.artistId " +
            "INNER JOIN track_instances instances " +
            "ON instances.instanceId = links.instanceId " +
            "WHERE instances.songId = :songId " +
            "GROUP BY catalog_artists.artistId, catalog_artists.name " +
            "ORDER BY MIN(links.position)",
    )
    abstract suspend fun getArtistsForSong(songId: String): List<CatalogArtistEntity>

    @Query(
        "SELECT metadata.externalId AS externalId, artists.name AS name " +
            "FROM track_instances instances " +
            "INNER JOIN track_instance_artists links " +
            "ON links.instanceId = instances.instanceId " +
            "INNER JOIN catalog_artists artists " +
            "ON artists.artistId = links.artistId " +
            "INNER JOIN catalog_artist_metadata metadata " +
            "ON metadata.artistId = artists.artistId AND metadata.source = :source " +
            "WHERE instances.songId = :songId " +
            "GROUP BY metadata.externalId, artists.name " +
            "ORDER BY MIN(links.position)",
    )
    abstract fun observeArtistSourceRefsForSong(
        songId: String,
        source: String,
    ): Flow<List<CatalogArtistSourceRef>>

    @Query("DELETE FROM track_instance_artists WHERE instanceId = :instanceId")
    abstract suspend fun deleteTrackInstanceArtists(instanceId: String)

    @Upsert
    protected abstract suspend fun upsertTrackInstanceArtists(
        links: List<TrackInstanceArtistEntity>,
    )

    /**
     * Возвращает внутренний ID артиста, создавая source-привязку отдельно от него.
     * Пустая метадата означает известную идентичность ЯМ, но ещё не загруженный профиль.
     */
    @Transaction
    open suspend fun ensureYandexArtist(
        yandexId: Int,
        name: String,
    ): String {
        val externalId = yandexId.toString()
        val storedMetadata = getArtistMetadataByExternalId(
            source = CATALOG_SOURCE_YANDEX,
            externalId = externalId,
        )
        val artistId = storedMetadata?.artistId ?: UUID.randomUUID().toString()
        upsertArtist(CatalogArtistEntity(artistId = artistId, name = name))
        if (storedMetadata == null) {
            upsertArtistMetadata(
                CatalogArtistMetadataEntity(
                    artistId = artistId,
                    source = CATALOG_SOURCE_YANDEX,
                    externalId = externalId,
                    coverUri = null,
                    genres = "",
                    likesCount = null,
                    trackCount = null,
                    lastMonthListeners = null,
                    lastMonthListenersDelta = null,
                    refreshedAt = 0L,
                ),
            )
        }
        return artistId
    }

    /** Атомарно заменяет список подтверждённых ЯМ артистов одного source-инстанса. */
    @Transaction
    open suspend fun replaceTrackArtistsFromYandex(
        instanceId: String,
        artists: List<dYaArtist>,
    ) {
        val links = artists
            .filter { artist -> artist.id != null }
            .distinctBy { artist -> artist.id }
            .mapIndexed { position, artist ->
                TrackInstanceArtistEntity(
                    instanceId = instanceId,
                    artistId = ensureYandexArtist(
                        yandexId = requireNotNull(artist.id),
                        name = artist.name,
                    ),
                    position = position,
                )
            }
        deleteTrackInstanceArtists(instanceId)
        if (links.isNotEmpty()) {
            upsertTrackInstanceArtists(links)
        }
    }

    @Upsert
    abstract suspend fun upsertAlbum(album: CatalogAlbumEntity)

    @Upsert
    abstract suspend fun upsertAlbumMetadata(metadata: CatalogAlbumMetadataEntity)

    @Query("SELECT * FROM catalog_albums WHERE albumId = :albumId LIMIT 1")
    abstract suspend fun getAlbum(albumId: String): CatalogAlbumEntity?

    @Query(
        "SELECT * FROM catalog_album_metadata " +
            "WHERE albumId = :albumId AND source = :source LIMIT 1",
    )
    abstract suspend fun getAlbumMetadata(
        albumId: String,
        source: String,
    ): CatalogAlbumMetadataEntity?

    @Query(
        "SELECT * FROM catalog_album_metadata " +
            "WHERE source = :source AND externalId = :externalId LIMIT 1",
    )
    abstract suspend fun getAlbumMetadataByExternalId(
        source: String,
        externalId: String,
    ): CatalogAlbumMetadataEntity?

    @Query(
        "SELECT catalog_albums.* FROM catalog_albums " +
            "INNER JOIN track_instance_albums links " +
            "ON links.albumId = catalog_albums.albumId " +
            "INNER JOIN track_instances instances " +
            "ON instances.instanceId = links.instanceId " +
            "WHERE instances.songId = :songId " +
            "GROUP BY catalog_albums.albumId, catalog_albums.title " +
            "ORDER BY MIN(links.position)",
    )
    abstract suspend fun getAlbumsForSong(songId: String): List<CatalogAlbumEntity>

    @Query("DELETE FROM track_instance_albums WHERE instanceId = :instanceId")
    abstract suspend fun deleteTrackInstanceAlbums(instanceId: String)

    @Upsert
    protected abstract suspend fun upsertTrackInstanceAlbums(
        links: List<TrackInstanceAlbumEntity>,
    )

    /** Возвращает внутренний ID альбома, сохраняя внешний ID только в source-метадате. */
    @Transaction
    open suspend fun ensureYandexAlbum(
        yandexId: Int,
        title: String,
    ): String {
        val externalId = yandexId.toString()
        val storedMetadata = getAlbumMetadataByExternalId(
            source = CATALOG_SOURCE_YANDEX,
            externalId = externalId,
        )
        val albumId = storedMetadata?.albumId ?: UUID.randomUUID().toString()
        upsertAlbum(CatalogAlbumEntity(albumId = albumId, title = title))
        if (storedMetadata == null) {
            upsertAlbumMetadata(
                CatalogAlbumMetadataEntity(
                    albumId = albumId,
                    source = CATALOG_SOURCE_YANDEX,
                    externalId = externalId,
                    coverUri = null,
                    artistNames = "",
                    genre = null,
                    releaseDate = null,
                    year = null,
                    type = null,
                    description = null,
                    likesCount = null,
                    trackCount = null,
                    refreshedAt = 0L,
                ),
            )
        }
        return albumId
    }

    /** Атомарно заменяет альбомы, определённые из метадаты ЯМ для source-инстанса. */
    @Transaction
    open suspend fun replaceTrackAlbumsFromYandex(
        instanceId: String,
        albums: List<dYaAlbum>,
    ) {
        val links = albums
            .distinctBy(dYaAlbum::id)
            .mapIndexed { position, album ->
                TrackInstanceAlbumEntity(
                    instanceId = instanceId,
                    albumId = ensureYandexAlbum(
                        yandexId = album.id,
                        title = album.title,
                    ),
                    position = position,
                )
            }
        deleteTrackInstanceAlbums(instanceId)
        if (links.isNotEmpty()) {
            upsertTrackInstanceAlbums(links)
        }
    }

    @Query(
        "SELECT * FROM catalog_album_tracks " +
            "WHERE albumId = :albumId AND source = :source ORDER BY position",
    )
    abstract suspend fun getAlbumTracks(
        albumId: String,
        source: String,
    ): List<CatalogAlbumTrackEntity>

    @Query(
        "DELETE FROM catalog_album_tracks " +
            "WHERE albumId = :albumId AND source = :source",
    )
    protected abstract suspend fun deleteAlbumTracks(
        albumId: String,
        source: String,
    )

    @Upsert
    protected abstract suspend fun upsertAlbumTracks(
        tracks: List<CatalogAlbumTrackEntity>,
    )

    /** Атомарно заменяет порядок треков одного source-снимка альбома. */
    @Transaction
    open suspend fun replaceAlbumTracks(
        albumId: String,
        source: String,
        tracks: List<CatalogAlbumTrackEntity>,
    ) {
        deleteAlbumTracks(albumId, source)
        if (tracks.isNotEmpty()) {
            upsertAlbumTracks(tracks)
        }
    }
}
