package com.yellastrodev.dwij.data.source

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.yellastrodev.dwij.entities.dTrackAlbumCrossRef
import com.yellastrodev.dwij.entities.dTrackArtistCrossRef
import com.yellastrodev.dwij.entities.dYaAlbum
import com.yellastrodev.dwij.entities.dYaArtist
import com.yellastrodev.dwij.entities.dYaPlaylist
import com.yellastrodev.dwij.entities.dYaTrack

@Dao
interface dTrackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: dYaTrack)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbum(album: dYaAlbum)

    @Query("SELECT * FROM artists WHERE id IS NULL AND name = :name LIMIT 1")
    suspend fun findLocalArtistByName(name: String): dYaArtist?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtistInternal(artist: dYaArtist): Long

    @Transaction
    suspend fun insertArtist(artist: dYaArtist): Long {
        // Если id == null, проверяем по имени
        if (artist.id == null) {
            val existing = findLocalArtistByName(artist.name)
            if (existing != null) {
                return existing.localId // уже есть — возвращаем существующий PK
            }
        }
        return insertArtistInternal(artist)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackAlbumCrossRef(ref: dTrackAlbumCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackArtistCrossRef(ref: dTrackArtistCrossRef)

    @Transaction
    suspend fun insert(
        track: dYaTrack
    ) {
        insertTrack(track)
        track.albums.forEach {
            insertAlbum(it)
            insertTrackAlbumCrossRef(dTrackAlbumCrossRef(track.id, it.id))
        }
        track.artists.forEach {
            val localId = insertArtist(it)
            insertTrackArtistCrossRef(dTrackArtistCrossRef(track.id, localId))
        }
    }

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun getTrackEntity(id: String): dYaTrack?

    @Query("""
        SELECT a.* FROM albums a
        INNER JOIN track_albums ta ON a.id = ta.albumId
        WHERE ta.trackId = :trackId
    """)
    suspend fun getAlbumsForTrack(trackId: String): List<dYaAlbum>

    @Query("""
        SELECT ar.* FROM artists ar
        INNER JOIN track_artists tar ON ar.localId = tar.artistLocalId
        WHERE tar.trackId = :trackId
    """)
    suspend fun getArtistsForTrack(trackId: String): List<dYaArtist>

    @Transaction
    suspend fun getTrack(id: String): dYaTrack? {
        val track = getTrackEntity(id) ?: return null
        val albums = getAlbumsForTrack(id)
        val artists = getArtistsForTrack(id)
        return track.apply { this.albums = albums; this.artists = artists }
    }

    @Query("SELECT * FROM tracks")
    suspend fun getAllTracksDump(): List<dYaTrack>

    @Transaction
    suspend fun getAllTracks(): List<dYaTrack> {
        val tracks = getAllTracksDump()
        tracks.forEach { track ->
            track.artists = getArtistsForTrack(track.id)
            track.albums = getAlbumsForTrack(track.id) }
        return tracks
    }
    suspend fun insertAll(tracks: List<dYaTrack>) {
        tracks.forEach { insert(it) }
    }

    @Query("DELETE FROM tracks WHERE id = :string")
    fun delete(string: String)
}
