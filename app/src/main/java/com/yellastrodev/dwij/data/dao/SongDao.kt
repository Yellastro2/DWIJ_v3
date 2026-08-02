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
import com.yellastrodev.dwij.data.entities.MusicSource
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

    @Query("SELECT * FROM songs WHERE matchKey = :matchKey LIMIT 1")
    abstract suspend fun findByMatchKey(matchKey: String): SongEntity?

    @Query("SELECT * FROM songs WHERE songId = :songId LIMIT 1")
    abstract suspend fun getSong(songId: String): SongEntity?

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

    /** Атомарно находит либо создаёт песню и привязывает к ней source-экземпляр. */
    @Transaction
    open suspend fun link(song: SongEntity, instance: TrackInstanceEntity): String {
        val existing = findByMatchKey(song.matchKey)
        val songId = existing?.songId ?: run {
            insertSong(song)
            findByMatchKey(song.matchKey)?.songId ?: song.songId
        }
        val stored = getSong(songId)
        if (stored != null) {
            val updated = if (instance.source == MusicSource.YANDEX.name) {
                stored.copy(
                    title = song.title,
                    artistNames = song.artistNames,
                    albumTitle = song.albumTitle ?: stored.albumTitle,
                    durationMs = song.durationMs ?: stored.durationMs,
                    coverUri = song.coverUri ?: stored.coverUri,
                )
            } else {
                stored.copy(
                    albumTitle = stored.albumTitle ?: song.albumTitle,
                    durationMs = stored.durationMs ?: song.durationMs,
                    coverUri = stored.coverUri ?: song.coverUri,
                )
            }
            if (updated != stored) updateSong(updated)
        }
        upsertInstance(instance.copy(songId = songId))
        return songId
    }
}
