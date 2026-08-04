package com.yellastrodev.dwij.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.yellastrodev.dwij.data.entities.SongMatchCandidateEntity
import com.yellastrodev.dwij.data.entities.SongMatchCandidateStatus
import kotlinx.coroutines.flow.Flow
import kotlin.collections.forEach

@Dao
abstract class SongMatchDao {
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
