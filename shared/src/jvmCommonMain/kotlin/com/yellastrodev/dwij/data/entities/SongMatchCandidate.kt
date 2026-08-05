package com.yellastrodev.dwij.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

enum class SongMatchCandidateStatus {
    PENDING,
    REJECTED,
}

/**
 * Неподтверждённое сходство двух независимых [SongEntity].
 * Resolver никогда не объединяет песни сам: решение остаётся за пользователем.
 */
@Entity(
    tableName = "song_match_candidates",
    primaryKeys = ["firstSongId", "secondSongId"],
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["songId"],
            childColumns = ["firstSongId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["songId"],
            childColumns = ["secondSongId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("firstSongId"),
        Index("secondSongId"),
        Index("status"),
    ],
)
data class SongMatchCandidateEntity(
    val firstSongId: String,
    val secondSongId: String,
    val titleSimilarity: Float,
    val artistSimilarity: Float,
    val score: Float,
    val resolverVersion: Int,
    val status: String = SongMatchCandidateStatus.PENDING.name,
)
