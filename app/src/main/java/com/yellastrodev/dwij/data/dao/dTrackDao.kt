package com.yellastrodev.dwij.data.dao

import android.util.Log
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.yellastrodev.dwij.data.entities.dTrackAlbumCrossRef
import com.yellastrodev.dwij.data.entities.dTrackArtistCrossRef
import com.yellastrodev.dwij.data.entities.dYaAlbum
import com.yellastrodev.dwij.data.entities.dYaArtist
import com.yellastrodev.dwij.data.entities.dYaTrack

@Dao
interface dTrackDao {


    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrack(track: dYaTrack)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlbum(album: dYaAlbum)

    @Query("SELECT * FROM artists WHERE id IS NULL AND name = :name LIMIT 1")
    suspend fun findLocalArtistByName(name: String): dYaArtist?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArtistInternal(artist: dYaArtist): Long

    @Query("SELECT * FROM artists WHERE id = :id LIMIT 1")
    suspend fun findArtistById(id: Int): dYaArtist?

    @Transaction
    suspend fun insertArtist(artist: dYaArtist): Long {
        // Если есть внешний id — проверяем по нему
        if (artist.id != null) {
            val existingById = findArtistById(artist.id)
            if (existingById != null) {
                return existingById.localId // уже есть — возвращаем существующий PK
            }
        } else {
            // Если id == null, проверяем по имени
            val existingByName = findLocalArtistByName(artist.name)
            if (existingByName != null) {
                return existingByName.localId // уже есть — возвращаем существующий PK
            }
        }

        // Если не нашли — вставляем
        return insertArtistInternal(artist)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrackAlbumCrossRef(ref: dTrackAlbumCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlbums(albums: List<dYaAlbum>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrackAlbumCrossRefs(refs: List<dTrackAlbumCrossRef>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrackArtistCrossRef(ref: dTrackArtistCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrackArtistCrossRefs(refs: List<dTrackArtistCrossRef>)

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

    @Query("SELECT id FROM tracks WHERE id IN (:ids)")
    suspend fun getExistingTrackIds(ids: List<String>): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun _insertTracks(tracks: List<dYaTrack>)

    @Transaction
    suspend fun insertAll(tracks: List<dYaTrack>) {
        if (tracks.isEmpty()) return

        // 1. Определяем, какие треки уже есть
        val existingIds = getExistingTrackIds(tracks.map { it.id }).toSet()
        val newTracks = tracks.filter { it.id !in existingIds }

        // 2. Вставляем только новые треки
        _insertTracks(newTracks)

        // 3. Собираем все альбомы и связи
        val allAlbums = tracks.flatMap { it.albums }.distinctBy { it.id }
        insertAlbums(allAlbums)

        val albumRefs = tracks.flatMap { track ->
            track.albums.map { album -> dTrackAlbumCrossRef(track.id, album.id) }
        }
        insertTrackAlbumCrossRefs(albumRefs)

        // 4. Артисты: вставляем по одному, чтобы сохранить insertArtist-логику
        val artistRefs = mutableListOf<dTrackArtistCrossRef>()
        for (track in tracks) {
            for (artist in track.artists) {
                val localId = insertArtist(artist) // твоя логика с поиском по имени
                artistRefs.add(dTrackArtistCrossRef(track.id, localId))
            }
        }
        insertTrackArtistCrossRefs(artistRefs)
    }

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun getTrackDump(id: String): dYaTrack?

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

    @Query("SELECT playlistUuid FROM playlist_tracks WHERE trackId = :trackId")
    suspend fun getPlaylistsForTrack(trackId: String): List<String>


    @Transaction
    suspend fun getTrack(id: String): dYaTrack? {
        val track = getTrackDump(id) ?: return null
        val albums = getAlbumsForTrack(id)
        val artists = getArtistsForTrack(id)
        val playlists = getPlaylistsForTrack(id)
        Log.d("dTrackDao", "getTrack: id=$id, playlists=${playlists.size}")
        return track.apply { this.albums = albums; this.artists = artists; this.playlists = playlists }
    }

    @Query("SELECT * FROM tracks")
    suspend fun getAllTracksDump(): List<dYaTrack>

    @Transaction
    suspend fun getAllTracks(): List<dYaTrack> {
        val tracks = getAllTracksDump()
        tracks.forEach { track ->
            track.artists = getArtistsForTrack(track.id)
            track.albums = getAlbumsForTrack(track.id)
            track.playlists = getPlaylistsForTrack(track.id)
            Log.d("dTrackDao", "getAllTracks: id=${track.id}, playlists=${track.playlists.size}")
        }
        return tracks
    }

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getTracksDump(ids: List<String>): List<dYaTrack>

    @Transaction
    suspend fun getTracks(ids: List<String>): List<dYaTrack> {
        if (ids.isEmpty()) return emptyList()

        val tracks = getTracksDump(ids)
        tracks.forEach { track ->
            track.artists = getArtistsForTrack(track.id)
            track.albums = getAlbumsForTrack(track.id)
            track.playlists = getPlaylistsForTrack(track.id)
            Log.d("dTrackDao", "getTracks: id=${track.id}, playlists=${track.playlists.size}")
        }
        return tracks
    }



    @Query("DELETE FROM tracks WHERE id = :string")
    fun delete(string: String)
}
