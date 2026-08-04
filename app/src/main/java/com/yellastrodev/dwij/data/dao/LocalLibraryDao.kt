package com.yellastrodev.dwij.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.yellastrodev.dwij.data.entities.LocalLibraryStateEntity
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntity
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntryEntity
import com.yellastrodev.dwij.data.entities.LocalPlaylistOrigin
import com.yellastrodev.dwij.data.entities.LocalPlaylistSummary
import com.yellastrodev.dwij.data.entities.LocalTrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LocalLibraryDao {
    @Query(
        "SELECT * FROM local_tracks WHERE isHidden = 0 " +
            "ORDER BY title COLLATE NOCASE, artist COLLATE NOCASE"
    )
    abstract fun observeAllTracks(): Flow<List<LocalTrackEntity>>

    /** Полный индекс нужен синхронизации, включая скрытые пользователем записи. */
    @Query("SELECT * FROM local_tracks ORDER BY title COLLATE NOCASE, artist COLLATE NOCASE")
    abstract suspend fun getAllTracks(): List<LocalTrackEntity>

    @Query(
        "SELECT * FROM local_tracks WHERE isHidden = 1 " +
            "ORDER BY title COLLATE NOCASE, artist COLLATE NOCASE"
    )
    abstract fun observeHiddenTracks(): Flow<List<LocalTrackEntity>>

    @Query("SELECT * FROM local_playlists ORDER BY name COLLATE NOCASE")
    abstract fun observePlaylists(): Flow<List<LocalPlaylistEntity>>

    /** Одним запросом считает строки плейлиста и длительность найденных локальных треков. */
    @Query(
        """
        SELECT local_playlists.*,
               COUNT(
                   CASE WHEN COALESCE(local_tracks.isHidden, 0) = 0
                       THEN local_playlist_entries.position
                   END
               ) AS trackCount,
               COALESCE(
                   SUM(
                       CASE WHEN local_tracks.isHidden = 0
                           THEN local_tracks.durationMs
                       END
                   ),
                   0
               ) AS durationMs
        FROM local_playlists
        LEFT JOIN local_playlist_entries
            ON local_playlist_entries.playlistId = local_playlists.playlistId
        LEFT JOIN local_tracks
            ON local_tracks.instanceId = local_playlist_entries.localTrackId
        GROUP BY local_playlists.playlistId
        ORDER BY local_playlists.name COLLATE NOCASE
        """
    )
    abstract fun observePlaylistSummaries(): Flow<List<LocalPlaylistSummary>>

    @Query("SELECT * FROM local_playlists WHERE playlistId = :playlistId")
    abstract fun observePlaylist(playlistId: String): Flow<LocalPlaylistEntity?>

    @Query("SELECT * FROM local_playlists WHERE playlistId = :playlistId")
    abstract suspend fun getPlaylist(playlistId: String): LocalPlaylistEntity?

    @Query("SELECT * FROM local_playlists WHERE origin = :origin")
    abstract suspend fun getPlaylistsByOrigin(
        origin: String = LocalPlaylistOrigin.DWIJ.name,
    ): List<LocalPlaylistEntity>

    @Query(
        """
        SELECT local_tracks.* FROM local_playlist_entries
        INNER JOIN local_tracks
            ON local_tracks.instanceId = local_playlist_entries.localTrackId
        WHERE local_playlist_entries.playlistId = :playlistId
          AND local_tracks.isHidden = 0
        ORDER BY local_playlist_entries.position
        """
    )
    abstract fun observePlaylistTracks(playlistId: String): Flow<List<LocalTrackEntity>>

    @Query("SELECT * FROM local_playlist_entries WHERE playlistId = :playlistId ORDER BY position")
    abstract suspend fun getPlaylistEntries(playlistId: String): List<LocalPlaylistEntryEntity>

    @Query(
        """
        SELECT local_playlist_entries.* FROM local_playlist_entries
        INNER JOIN local_playlists
            ON local_playlists.playlistId = local_playlist_entries.playlistId
        WHERE local_playlists.origin = :origin
          AND local_playlist_entries.localTrackId IS NULL
        """
    )
    abstract suspend fun getUnresolvedDwijEntries(
        origin: String = LocalPlaylistOrigin.DWIJ.name,
    ): List<LocalPlaylistEntryEntity>

    @Query("SELECT * FROM local_tracks WHERE instanceId IN (:trackIds)")
    abstract suspend fun getTracks(trackIds: List<String>): List<LocalTrackEntity>

    @Query("UPDATE local_tracks SET isHidden = :isHidden WHERE instanceId = :instanceId")
    abstract suspend fun setTrackHidden(instanceId: String, isHidden: Boolean): Int

    @Query("UPDATE local_tracks SET isHidden = :isHidden WHERE instanceId IN (:instanceIds)")
    abstract suspend fun setTracksHidden(instanceIds: List<String>, isHidden: Boolean): Int

    @Upsert
    abstract suspend fun upsertTracks(tracks: List<LocalTrackEntity>)

    @Query("DELETE FROM local_tracks")
    abstract suspend fun deleteAllTracks()

    @Query("DELETE FROM local_tracks WHERE instanceId IN (:trackIds)")
    abstract suspend fun deleteTracks(trackIds: List<String>)

    @Upsert
    abstract suspend fun upsertPlaylists(playlists: List<LocalPlaylistEntity>)

    @Upsert
    abstract suspend fun upsertPlaylist(playlist: LocalPlaylistEntity)

    @Query("DELETE FROM local_playlists WHERE origin != :dwijOrigin AND playlistId NOT IN (:activeIds)")
    abstract suspend fun deleteImportedPlaylistsExcept(
        activeIds: List<String>,
        dwijOrigin: String = LocalPlaylistOrigin.DWIJ.name,
    )

    @Query("DELETE FROM local_playlists WHERE origin != :dwijOrigin")
    abstract suspend fun deleteAllImportedPlaylists(
        dwijOrigin: String = LocalPlaylistOrigin.DWIJ.name,
    )

    @Query("DELETE FROM local_playlist_entries WHERE playlistId = :playlistId")
    abstract suspend fun deletePlaylistEntries(playlistId: String)

    @Upsert
    abstract suspend fun upsertPlaylistEntries(entries: List<LocalPlaylistEntryEntity>)

    @Query("SELECT value FROM local_library_state WHERE `key` = :key")
    abstract suspend fun getState(key: String): String?

    @Upsert
    abstract suspend fun putState(state: LocalLibraryStateEntity)

    /** Применяет только целиком собранный снимок, не оставляя половинчатого индекса. */
    @Transaction
    open suspend fun applyMediaSnapshotDiff(
        tracksToUpsert: List<LocalTrackEntity>,
        trackIdsToDelete: List<String>,
        playlists: List<LocalPlaylistEntity>,
        entries: List<LocalPlaylistEntryEntity>,
        generation: String,
    ) {
        if (tracksToUpsert.isNotEmpty()) upsertTracks(tracksToUpsert)
        if (trackIdsToDelete.isNotEmpty()) deleteTracks(trackIdsToDelete)

        if (playlists.isEmpty()) {
            deleteAllImportedPlaylists()
        } else {
            upsertPlaylists(playlists)
            deleteImportedPlaylistsExcept(playlists.map(LocalPlaylistEntity::playlistId))
            playlists.forEach { deletePlaylistEntries(it.playlistId) }
            if (entries.isNotEmpty()) upsertPlaylistEntries(entries)
        }
        putState(LocalLibraryStateEntity(LAST_GENERATION_KEY, generation))
    }

    @Transaction
    open suspend fun replaceDwijPlaylist(
        playlist: LocalPlaylistEntity,
        entries: List<LocalPlaylistEntryEntity>,
    ) {
        upsertPlaylist(playlist)
        deletePlaylistEntries(playlist.playlistId)
        if (entries.isNotEmpty()) upsertPlaylistEntries(entries)
    }

    companion object {
        const val LAST_GENERATION_KEY = "media_store_generation"
    }
}
