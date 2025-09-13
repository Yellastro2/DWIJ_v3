package com.yellastrodev.dwij.data.source

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.yellastrodev.dwij.entities.dPlaylistTrack
import com.yellastrodev.dwij.entities.dYaPlaylist

@Dao
interface dPlaylistDao {

//    @Query("SELECT * FROM playlists WHERE playlistUuid = :id LIMIT 1")
//    suspend fun getdPlaylistById(id: String): dYaPlaylist?

    @Query("SELECT * FROM playlists")
    suspend fun getAlldPlaylistsDump(): List<dYaPlaylist>

//    @Insert(onConflict = OnConflictStrategy.REPLACE)
//    suspend fun insert(playlist: dYaPlaylist)

//    @Insert(onConflict = OnConflictStrategy.REPLACE)
//    suspend fun insertAll(playlists: List<dYaPlaylist>)

    @Query("DELETE FROM playlists WHERE playlistUuid = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM playlists")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<dPlaylistTrack>)

    @Query("SELECT * FROM playlists WHERE playlistUuid = :id LIMIT 1")
    suspend fun getPlaylistEntity(id: String): dYaPlaylist?

    @Query("SELECT * FROM playlist_tracks WHERE playlistUuid = :id")
    suspend fun getTracksForPlaylist(id: String): List<dPlaylistTrack>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistEntity(playlist: dYaPlaylist)

    @Transaction
    suspend fun insert(playlist: dYaPlaylist) {
        insertPlaylistEntity(playlist)
        insertTracks(playlist.tracks.map { it.copy(playlistUuid = playlist.playlistUuid) })
    }

    @Transaction
    suspend fun getdPlaylistById(id: String): dYaPlaylist? {
        val pl = getPlaylistEntity(id) ?: return null
        pl.tracks = getTracksForPlaylist(id)
        return pl
    }

    @Transaction
    suspend fun getAlldPlaylists(): List<dYaPlaylist> {
        val playlists = getAlldPlaylistsDump()
        return playlists.map { pl ->
            pl.tracks = getTracksForPlaylist(pl.playlistUuid)
            pl
        }
    }

    @Transaction
    suspend fun insertAll(playlists: List<dYaPlaylist>) {
        playlists.forEach { pl ->
            insert(pl)
        }
    }

}
