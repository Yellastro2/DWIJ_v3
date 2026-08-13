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
