package com.yellastrodev.dwij.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.yellastrodev.dwij.data.entities.dPlaylistTrack
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import kotlin.collections.forEach

@Dao
interface dPlaylistDao {

    val TAG: String
        get() = "dPlaylistDao"

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

    @Query("DELETE FROM playlist_tracks WHERE playlistUuid = :playlistUuid")
    suspend fun deleteTracksForPlaylist(playlistUuid: String)

    @Query("SELECT * FROM playlists WHERE playlistUuid = :id LIMIT 1")
    suspend fun getPlaylistEntity(id: String): dYaPlaylist?

    @Query(
        "SELECT * FROM playlist_tracks " +
            "WHERE playlistUuid = :id ORDER BY position ASC"
    )
    suspend fun getTracksForPlaylist(id: String): List<dPlaylistTrack>

    /** Проверяет статус по уже закоммиченным строкам локального liked-плейлиста. */
    @Query(
        "SELECT EXISTS(" +
            "SELECT 1 FROM playlist_tracks " +
            "INNER JOIN playlists " +
            "ON playlists.playlistUuid = playlist_tracks.playlistUuid " +
            "WHERE playlists.kind = 'liked' AND playlist_tracks.trackId = :trackId" +
        ")"
    )
    suspend fun isTrackLiked(trackId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistDump(playlist: dYaPlaylist)

    @Transaction
    suspend fun insert(playlist: dYaPlaylist) {
        insertPlaylistDump(playlist)
        deleteTracksForPlaylist(playlist.playlistUuid)
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
