package com.yellastrodev.dwij.data.dao

import androidx.room.Dao
import androidx.room.ColumnInfo
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
    @ColumnInfo(name = "isLiked") val isLiked: Boolean,
)

@Dao
abstract class SongDao {
    @Transaction
    @Query(
        "SELECT songs.*, " + IS_LIKED_SQL + " AS isLiked FROM songs " +
            "ORDER BY title COLLATE NOCASE, artistNames COLLATE NOCASE"
    )
    abstract fun observeSongs(): Flow<List<SongWithInstances>>

    @Transaction
    @Query(
        "SELECT DISTINCT songs.*, " + IS_LIKED_SQL + " AS isLiked FROM songs " +
            "INNER JOIN track_instances ON track_instances.songId = songs.songId " +
            "WHERE track_instances.source = :source " +
            "ORDER BY songs.title COLLATE NOCASE, songs.artistNames COLLATE NOCASE"
    )
    abstract fun observeSongsForSource(source: String): Flow<List<SongWithInstances>>

    /** Локальный каталог не показывает экземпляры, скрытые пользователем в Движе. */
    @Transaction
    @Query(
        "SELECT DISTINCT songs.*, " + IS_LIKED_SQL + " AS isLiked FROM songs " +
            "INNER JOIN track_instances ON track_instances.songId = songs.songId " +
            "INNER JOIN local_tracks ON local_tracks.instanceId = track_instances.sourceTrackId " +
            "WHERE track_instances.source = :source AND local_tracks.isHidden = 0 " +
            "ORDER BY songs.title COLLATE NOCASE, songs.artistNames COLLATE NOCASE"
    )
    abstract fun observeSongsForVisibleLocalTracks(source: String): Flow<List<SongWithInstances>>

    @Transaction
    @Query(
        "SELECT songs.*, " + IS_LIKED_SQL + " AS isLiked FROM songs " +
            "WHERE songId IN (:songIds)"
    )
    abstract suspend fun getSongs(songIds: List<String>): List<SongWithInstances>

    @Transaction
    @Query(
        "SELECT songs.*, " + IS_LIKED_SQL + " AS isLiked FROM songs " +
            "WHERE songId = :songId LIMIT 1"
    )
    abstract fun observeSong(songId: String): Flow<SongWithInstances?>

    @Query(
        "SELECT DISTINCT songs.songId FROM songs " +
            "INNER JOIN track_instances " +
            "ON track_instances.songId = songs.songId " +
            "INNER JOIN playlist_tracks " +
            "ON playlist_tracks.trackId = track_instances.sourceTrackId " +
            "INNER JOIN playlists " +
            "ON playlists.playlistUuid = playlist_tracks.playlistUuid " +
            "WHERE track_instances.source = 'YANDEX' AND playlists.kind = 'liked'"
    )
    abstract fun observeLikedSongIds(): Flow<List<String>>

    @Query("SELECT * FROM songs WHERE songId = :songId LIMIT 1")
    abstract suspend fun getSong(songId: String): SongEntity?

    @Transaction
    @Query("SELECT songs.*, " + IS_LIKED_SQL + " AS isLiked FROM songs")
    abstract suspend fun getAllSongs(): List<SongWithInstances>

    @Transaction
    @Query(
        "SELECT songs.*, " + IS_LIKED_SQL + " AS isLiked FROM songs " +
            "WHERE matchResolverVersion < :resolverVersion " +
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

    @Query(
        "SELECT tracks.id FROM tracks " +
            "LEFT JOIN track_instances ON track_instances.source = :source " +
            "AND track_instances.sourceTrackId = tracks.id " +
            "WHERE track_instances.instanceId IS NULL"
    )
    abstract suspend fun getUnindexedYandexTrackIds(source: String): List<String>

    @Query(
        "SELECT local_tracks.instanceId FROM local_tracks " +
            "LEFT JOIN track_instances ON track_instances.source = :source " +
            "AND track_instances.sourceTrackId = local_tracks.instanceId " +
            "WHERE track_instances.instanceId IS NULL"
    )
    abstract suspend fun getUnindexedLocalTrackIds(source: String): List<String>

    @Query("SELECT * FROM track_instances WHERE instanceId = :instanceId LIMIT 1")
    abstract suspend fun getInstance(instanceId: String): TrackInstanceEntity?

    @Query("SELECT * FROM track_instances WHERE instanceId IN (:instanceIds)")
    abstract suspend fun getInstancesByIds(instanceIds: List<String>): List<TrackInstanceEntity>

    @Query("SELECT * FROM track_instances WHERE songId IN (:songIds)")
    abstract suspend fun getInstancesForSongs(songIds: List<String>): List<TrackInstanceEntity>

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

    @Query("UPDATE track_instances SET songId = :targetSongId WHERE songId IN (:sourceSongIds)")
    abstract suspend fun moveInstancesToSong(
        sourceSongIds: List<String>,
        targetSongId: String,
    )

    @Query("DELETE FROM songs WHERE songId IN (:songIds)")
    abstract suspend fun deleteSongs(songIds: List<String>)

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
        "DELETE FROM track_instances " +
            "WHERE source = :source AND sourceTrackId IN (:sourceTrackIds)"
    )
    abstract suspend fun deleteInstances(source: String, sourceTrackIds: List<String>)

    @Query(
        "DELETE FROM songs WHERE NOT EXISTS (" +
            "SELECT 1 FROM track_instances WHERE track_instances.songId = songs.songId)"
    )
    abstract suspend fun deleteOrphanSongs()

    /**
     * Обновляет песню уже известного source-экземпляра либо создаёт для него отдельную [com.yellastrodev.dwij.data.entities.SongEntity].
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

    /**
     * Объединяет логические песни, которым принадлежат указанные экземпляры.
     *
     * Песня первого экземпляра сохраняется вместе со своей канонической метадатой. Все экземпляры
     * остальных затронутых песен переносятся к ней, а ставшие лишними [com.yellastrodev.dwij.data.entities.SongEntity] удаляются.
     * Таким образом уже существующая мультисурсная группа никогда не разделяется частично.
     */
    @Transaction
    open suspend fun mergeInstances(instanceIds: List<String>): String {
        val orderedInstanceIds = instanceIds.distinct()
        require(orderedInstanceIds.isNotEmpty()) {
            "Для объединения нужен хотя бы один экземпляр"
        }

        val requestedInstances = getInstancesByIds(orderedInstanceIds)
            .associateBy(TrackInstanceEntity::instanceId)
        val missingInstanceIds = orderedInstanceIds.filterNot(requestedInstances::containsKey)
        require(missingInstanceIds.isEmpty()) {
            "Экземпляры не найдены: ${missingInstanceIds.joinToString()}"
        }

        val targetSongId = requestedInstances.getValue(orderedInstanceIds.first()).songId
        val sourceSongIds = orderedInstanceIds
            .map { instanceId -> requestedInstances.getValue(instanceId).songId }
            .distinct()
        if (sourceSongIds.size == 1) return targetSongId

        val songsById = sourceSongIds.associateWith { songId ->
            requireNotNull(getSong(songId)) {
                "Для песни $songId отсутствует SongEntity"
            }
        }
        val mergedInstanceIds = getInstancesForSongs(sourceSongIds)
            .mapTo(mutableSetOf(), TrackInstanceEntity::instanceId)
        val preferredInstanceId = sourceSongIds
            .asSequence()
            .mapNotNull { songId -> songsById.getValue(songId).preferredInstanceId }
            .firstOrNull(mergedInstanceIds::contains)

        moveInstancesToSong(sourceSongIds, targetSongId)
        updateSong(
            songsById.getValue(targetSongId).copy(
                preferredInstanceId = preferredInstanceId,
                matchResolverVersion = 0,
            )
        )
        deleteSongs(sourceSongIds.filterNot { songId -> songId == targetSongId })
        return targetSongId
    }

    private companion object {
        const val IS_LIKED_SQL =
            "EXISTS (" +
                "SELECT 1 FROM track_instances liked_instance " +
                "INNER JOIN playlist_tracks liked_relation " +
                "ON liked_relation.trackId = liked_instance.sourceTrackId " +
                "INNER JOIN playlists liked_playlist " +
                "ON liked_playlist.playlistUuid = liked_relation.playlistUuid " +
                "WHERE liked_instance.songId = songs.songId " +
                "AND liked_instance.source = 'YANDEX' " +
                "AND liked_playlist.kind = 'liked'" +
            ")"
    }
}
