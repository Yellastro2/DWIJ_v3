package com.yellastrodev.dwij.data.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.yellastrodev.dwij.data.entities.SongEntity
import com.yellastrodev.dwij.data.entities.TrackInstanceEntity
import kotlinx.coroutines.flow.Flow

data class SongWithInstances(
    @Embedded val song: SongEntity,
    @Relation(
        parentColumn = "songId",
        entityColumn = "songId",
    )
    val instances: List<TrackInstanceEntity>,
)

@Dao
abstract class SongDao {
    @Transaction
    @Query("SELECT * FROM songs ORDER BY title COLLATE NOCASE, artistNames COLLATE NOCASE")
    abstract fun observeSongs(): Flow<List<SongWithInstances>>

    @Transaction
    @Query("SELECT * FROM songs WHERE songId IN (:songIds)")
    abstract suspend fun getSongs(songIds: List<String>): List<SongWithInstances>

    @Query("SELECT * FROM songs WHERE songId = :songId LIMIT 1")
    abstract suspend fun getSong(songId: String): SongEntity?

    @Transaction
    @Query("SELECT * FROM songs")
    abstract suspend fun getAllSongs(): List<SongWithInstances>

    @Transaction
    @Query(
        "SELECT * FROM songs WHERE matchResolverVersion < :resolverVersion " +
            "ORDER BY songId LIMIT :limit"
    )
    abstract suspend fun getUnscannedSongs(
        resolverVersion: Int,
        limit: Int,
    ): List<SongWithInstances>

    @Query("SELECT COUNT(*) FROM songs WHERE matchResolverVersion < :resolverVersion")
    abstract fun observeUnscannedSongCount(resolverVersion: Int): Flow<Int>

    @Query(
        "SELECT * FROM track_instances " +
            "WHERE source = :source AND sourceTrackId IN (:sourceTrackIds)"
    )
    abstract suspend fun getInstances(
        source: String,
        sourceTrackIds: List<String>,
    ): List<TrackInstanceEntity>

    @Query("SELECT * FROM track_instances WHERE instanceId = :instanceId LIMIT 1")
    abstract suspend fun getInstance(instanceId: String): TrackInstanceEntity?

    @Query("UPDATE songs SET preferredInstanceId = :instanceId WHERE songId = :songId")
    abstract suspend fun updatePreferredInstance(songId: String, instanceId: String?)

    @Query("UPDATE songs SET matchResolverVersion = :resolverVersion WHERE songId = :songId")
    abstract suspend fun markResolverVersion(songId: String, resolverVersion: Int)

    @Query(
        "UPDATE songs SET matchResolverVersion = :resolverVersion " +
            "WHERE songId = :songId AND matchKey = :expectedMatchKey"
    )
    abstract suspend fun markResolverVersionIfUnchanged(
        songId: String,
        expectedMatchKey: String,
        resolverVersion: Int,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertSong(song: SongEntity): Long

    @Update
    abstract suspend fun updateSong(song: SongEntity)

    @Upsert
    abstract suspend fun upsertInstance(instance: TrackInstanceEntity)

    @Query(
        "DELETE FROM track_instances " +
            "WHERE source = :source AND sourceTrackId NOT IN (:activeSourceTrackIds)"
    )
    abstract suspend fun deleteMissingInstances(
        source: String,
        activeSourceTrackIds: List<String>,
    )

    @Query(
        "DELETE FROM track_instances WHERE source = :source"
    )
    abstract suspend fun deleteAllInstances(source: String)

    @Query(
        "DELETE FROM songs WHERE NOT EXISTS (" +
            "SELECT 1 FROM track_instances WHERE track_instances.songId = songs.songId)"
    )
    abstract suspend fun deleteOrphanSongs()

    /**
     * Обновляет песню уже известного source-экземпляра либо создаёт для него отдельную [SongEntity].
     * Сходство метаданных намеренно не используется для автоматического объединения.
     */
    @Transaction
    open suspend fun link(song: SongEntity, instance: TrackInstanceEntity): String {
        val existingInstance = getInstance(instance.instanceId)
        if (existingInstance != null) {
            val stored = getSong(existingInstance.songId)
                ?: error("Для экземпляра ${instance.instanceId} отсутствует Song")
            val updated = stored.copy(
                matchKey = song.matchKey,
                title = song.title,
                artistNames = song.artistNames,
                albumTitle = song.albumTitle,
                durationMs = song.durationMs,
                coverUri = song.coverUri,
            )
            if (updated != stored) {
                updateSong(updated.copy(matchResolverVersion = 0))
            }
            upsertInstance(instance.copy(songId = stored.songId))
            return stored.songId
        }

        insertSong(song)
        upsertInstance(instance.copy(songId = song.songId))
        return song.songId
    }
}
