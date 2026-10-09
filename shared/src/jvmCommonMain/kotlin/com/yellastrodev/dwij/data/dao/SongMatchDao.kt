package com.yellastrodev.dwij.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.yellastrodev.dwij.data.entities.SongMatchCandidateEntity
import com.yellastrodev.dwij.data.entities.SongMatchCandidateStatus
import com.yellastrodev.dwij.data.entities.MusicSource
import kotlinx.coroutines.flow.Flow
import kotlin.collections.forEach

/** Хранит предложения и пользовательские решения; автоматическое слияние проверяет пару в транзакции. */
@Dao
abstract class SongMatchDao {
    /** Объединяет только актуальную PENDING-пару с непересекающимися источниками, сохраняя ручные отказы. */
    @Transaction
    open suspend fun mergePendingCandidate(
        songDao: SongDao,
        firstSongId: String,
        secondSongId: String,
    ): String? {
        val candidate = getCandidate(firstSongId, secondSongId) ?: return null
        if (candidate.status != SongMatchCandidateStatus.PENDING.name) return null
        val songs = songDao.getSongs(listOf(firstSongId, secondSongId)).associateBy { it.song.songId }
        val first = songs[firstSongId] ?: return null
        val second = songs[secondSongId] ?: return null
        val supported = MusicSource.entries.mapTo(mutableSetOf()) { it.name }
        val firstSources = first.instances.mapTo(mutableSetOf()) { it.source }
        val secondSources = second.instances.mapTo(mutableSetOf()) { it.source }
        if (firstSources.isEmpty() || secondSources.isEmpty() ||
            !supported.containsAll(firstSources + secondSources) ||
            firstSources.intersect(secondSources).isNotEmpty()
        ) return null
        val relatedCandidates = getCandidatesForSongs(listOf(firstSongId, secondSongId))
        val mergedId = songDao.mergeInstances((first.instances + second.instances).map { it.instanceId })
        relatedCandidates.forEach { related ->
            val firstId = if (related.firstSongId == firstSongId || related.firstSongId == secondSongId)
                mergedId else related.firstSongId
            val secondId = if (related.secondSongId == firstSongId || related.secondSongId == secondSongId)
                mergedId else related.secondSongId
            if (firstId == secondId) return@forEach
            val remapped = related.copy(firstSongId = minOf(firstId, secondId), secondSongId = maxOf(firstId, secondId))
            insertCandidate(remapped)
            if (remapped.status == SongMatchCandidateStatus.REJECTED.name) {
                rejectCandidate(remapped.firstSongId, remapped.secondSongId)
            }
        }
        return mergedId
    }

    /** Снимок рёбер группы для переноса предложений и отказов после удаления одной из Song. */
    @Query("SELECT * FROM song_match_candidates WHERE firstSongId IN (:songIds) OR secondSongId IN (:songIds)")
    abstract suspend fun getCandidatesForSongs(songIds: List<String>): List<SongMatchCandidateEntity>

    /** Наблюдает все решения: ожидающие идут первыми, затем остальные по убыванию score. */
    @Query(
        "SELECT * FROM song_match_candidates " +
            "ORDER BY CASE WHEN status = :pendingStatus THEN 0 ELSE 1 END, score DESC"
    )
    abstract fun observeAllCandidates(
        pendingStatus: String = SongMatchCandidateStatus.PENDING.name,
    ): Flow<List<SongMatchCandidateEntity>>

    /** Наблюдает только ID песен, участвующих хотя бы в одном ожидающем решении. */
    @Query(
        "SELECT firstSongId AS songId FROM song_match_candidates WHERE status = :status " +
            "UNION SELECT secondSongId AS songId FROM song_match_candidates WHERE status = :status"
    )
    abstract fun observePendingSongIds(
        status: String = SongMatchCandidateStatus.PENDING.name,
    ): Flow<List<String>>

    /** Возвращает компактный снимок ID для нереактивной сборки конкретной очереди. */
    @Query(
        "SELECT firstSongId AS songId FROM song_match_candidates WHERE status = :status " +
            "UNION SELECT secondSongId AS songId FROM song_match_candidates WHERE status = :status"
    )
    abstract suspend fun getPendingSongIds(
        status: String = SongMatchCandidateStatus.PENDING.name,
    ): List<String>

    @Query(
        "SELECT * FROM song_match_candidates WHERE status = :status " +
            "ORDER BY score DESC"
    )
    abstract fun observePendingCandidates(
        status: String = SongMatchCandidateStatus.PENDING.name,
    ): Flow<List<SongMatchCandidateEntity>>

    @Query(
        "SELECT * FROM song_match_candidates " +
            "WHERE status = :status AND (firstSongId = :songId OR secondSongId = :songId) " +
            "ORDER BY score DESC"
    )
    abstract fun observePendingCandidatesForSong(
        songId: String,
        status: String = SongMatchCandidateStatus.PENDING.name,
    ): Flow<List<SongMatchCandidateEntity>>

    @Query("SELECT COUNT(*) FROM song_match_candidates WHERE status = :status")
    abstract suspend fun getPendingCandidateCount(
        status: String = SongMatchCandidateStatus.PENDING.name,
    ): Int

    @Query(
        "SELECT * FROM song_match_candidates " +
            "WHERE firstSongId = :firstSongId AND secondSongId = :secondSongId LIMIT 1"
    )
    abstract suspend fun getCandidate(
        firstSongId: String,
        secondSongId: String,
    ): SongMatchCandidateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertCandidate(candidate: SongMatchCandidateEntity): Long

    @Query(
        "UPDATE song_match_candidates SET " +
            "titleSimilarity = :titleSimilarity, artistSimilarity = :artistSimilarity, " +
            "score = :score, resolverVersion = :resolverVersion " +
            "WHERE firstSongId = :firstSongId AND secondSongId = :secondSongId " +
            "AND status = :pendingStatus"
    )
    abstract suspend fun updatePendingCandidate(
        firstSongId: String,
        secondSongId: String,
        titleSimilarity: Float,
        artistSimilarity: Float,
        score: Float,
        resolverVersion: Int,
        pendingStatus: String = SongMatchCandidateStatus.PENDING.name,
    )

    @Query(
        "DELETE FROM song_match_candidates WHERE status = :pendingStatus " +
            "AND (firstSongId = :songId OR secondSongId = :songId)"
    )
    abstract suspend fun deletePendingCandidatesForSong(
        songId: String,
        pendingStatus: String = SongMatchCandidateStatus.PENDING.name,
    )

    @Query(
        "UPDATE song_match_candidates SET status = :rejectedStatus " +
            "WHERE firstSongId = :firstSongId AND secondSongId = :secondSongId"
    )
    abstract suspend fun rejectCandidate(
        firstSongId: String,
        secondSongId: String,
        rejectedStatus: String = SongMatchCandidateStatus.REJECTED.name,
    )

    /** Обновляет только ожидающий кандидат; пользовательский REJECTED никогда не перезаписывает. */
    @Transaction
    open suspend fun savePendingCandidate(candidate: SongMatchCandidateEntity) {
        val existing = getCandidate(candidate.firstSongId, candidate.secondSongId)
        when {
            existing == null -> insertCandidate(candidate)
            existing.status == SongMatchCandidateStatus.PENDING.name -> updatePendingCandidate(
                firstSongId = candidate.firstSongId,
                secondSongId = candidate.secondSongId,
                titleSimilarity = candidate.titleSimilarity,
                artistSimilarity = candidate.artistSimilarity,
                score = candidate.score,
                resolverVersion = candidate.resolverVersion,
            )
        }
    }

    /** Атомарно заменяет автоматически найденные PENDING-пары одной песни. */
    @Transaction
    open suspend fun replacePendingCandidatesForSong(
        songId: String,
        candidates: List<SongMatchCandidateEntity>,
    ) {
        deletePendingCandidatesForSong(songId)
        candidates.forEach { candidate -> savePendingCandidate(candidate) }
    }
}
