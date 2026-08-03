package com.yellastrodev.dwij.data.repo

import android.util.Log
import com.yellastrodev.dwij.data.entities.SONG_ARTIST_SEPARATOR
import com.yellastrodev.dwij.data.entities.SongEntity
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

data class SongMatchScore(
    val titleSimilarity: Float,
    val artistSimilarity: Float,
    val total: Float,
)

/** Fuzzy-поиск: сравнивает только название и исполнителей, не объединяя песни. */
class SongMatchResolver {
    fun compare(first: SongEntity, second: SongEntity): SongMatchScore? {
        val comparisonNumber = comparisonCounter.incrementAndGet()
        val firstTitle = normalize(first.title)
        val secondTitle = normalize(second.title)
        val firstArtists = normalizeArtists(first.artistNames)
        val secondArtists = normalizeArtists(second.artistNames)
        if (
            firstTitle.isBlank() || secondTitle.isBlank() ||
            firstArtists.isBlank() || secondArtists.isBlank()
        ) {
            logRejected(
                comparisonNumber = comparisonNumber,
                first = first,
                second = second,
                reason = { "пустое название или исполнитель после нормализации" },
            )
            return null
        }

        val titleDistance = levenshteinDistance(firstTitle, secondTitle)
        val titleSimilarity = similarity(firstTitle, secondTitle, titleDistance)
        if (!isWithinTolerance(firstTitle, secondTitle, titleDistance, titleSimilarity, MIN_TITLE_SIMILARITY)) {
            logRejected(
                comparisonNumber = comparisonNumber,
                first = first,
                second = second,
                reason = {
                    "название: similarity=${titleSimilarity.formatScore()}, distance=$titleDistance"
                },
            )
            return null
        }
        val artistDistance = levenshteinDistance(firstArtists, secondArtists)
        val artistSimilarity = similarity(firstArtists, secondArtists, artistDistance)
        if (!isWithinTolerance(
                firstArtists,
                secondArtists,
                artistDistance,
                artistSimilarity,
                MIN_ARTIST_SIMILARITY,
            )
        ) {
            logRejected(
                comparisonNumber = comparisonNumber,
                first = first,
                second = second,
                reason = {
                    "исполнитель: similarity=${artistSimilarity.formatScore()}, distance=$artistDistance"
                },
            )
            return null
        }
        val total = titleSimilarity * TITLE_WEIGHT + artistSimilarity * ARTIST_WEIGHT
        Log.d(
            TAG,
            "[compare] #$comparisonNumber кандидат: " +
                "'${first.debugName()}' ↔ '${second.debugName()}', " +
                "title=${titleSimilarity.formatScore()}, " +
                "artist=${artistSimilarity.formatScore()}, total=${total.formatScore()}",
        )
        return SongMatchScore(
            titleSimilarity = titleSimilarity,
            artistSimilarity = artistSimilarity,
            total = total,
        )
    }

    /** Подробно показывает первые сравнения, затем оставляет редкие контрольные записи. */
    private inline fun logRejected(
        comparisonNumber: Long,
        first: SongEntity,
        second: SongEntity,
        reason: () -> String,
    ) {
        if (comparisonNumber > INITIAL_VERBOSE_COMPARISONS &&
            comparisonNumber % COMPARISON_LOG_INTERVAL != 0L
        ) {
            return
        }
        Log.d(
            TAG,
            "[compare] #$comparisonNumber отклонено: " +
                "'${first.debugName()}' ↔ '${second.debugName()}', причина=${reason()}",
        )
    }

    private fun SongEntity.debugName(): String = buildString {
        append(title.take(DEBUG_TEXT_LIMIT))
        val artists = artistNames.replace(SONG_ARTIST_SEPARATOR, ", ")
        if (artists.isNotBlank()) {
            append(" — ")
            append(artists.take(DEBUG_TEXT_LIMIT))
        }
    }

    private fun Float.formatScore(): String = String.format(Locale.ROOT, "%.3f", this)

    private fun normalizeArtists(value: String): String = value
        .split(SONG_ARTIST_SEPARATOR)
        .map(::normalize)
        .filter(String::isNotBlank)
        .sorted()
        .joinToString(" ")

    private fun normalize(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .replace(NON_ALPHANUMERIC, " ")
        .trim()
        .replace(MULTIPLE_SPACES, " ")

    private fun similarity(first: String, second: String, distance: Int): Float {
        if (first == second) return 1f
        val longest = max(first.length, second.length)
        if (longest == 0) return 1f
        return 1f - distance.toFloat() / longest
    }

    private fun isWithinTolerance(
        first: String,
        second: String,
        distance: Int,
        similarity: Float,
        minimumSimilarity: Float,
    ): Boolean = similarity >= minimumSimilarity ||
        (max(first.length, second.length) >= MIN_SINGLE_TYPO_LENGTH &&
            distance <= MAX_SINGLE_TYPO)

    private fun levenshteinDistance(first: String, second: String): Int {
        if (first.isEmpty()) return second.length
        if (second.isEmpty()) return first.length
        var previous = IntArray(second.length + 1) { it }
        var current = IntArray(second.length + 1)
        first.forEachIndexed { firstIndex, firstCharacter ->
            current[0] = firstIndex + 1
            second.forEachIndexed { secondIndex, secondCharacter ->
                val substitutionCost = if (firstCharacter == secondCharacter) 0 else 1
                current[secondIndex + 1] = minOf(
                    current[secondIndex] + 1,
                    previous[secondIndex + 1] + 1,
                    previous[secondIndex] + substitutionCost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[second.length]
    }

    private companion object {
        const val TAG = "SongMatchResolver"
        const val MIN_TITLE_SIMILARITY = 0.88f
        const val MIN_ARTIST_SIMILARITY = 0.88f
        const val MAX_SINGLE_TYPO = 1
        const val MIN_SINGLE_TYPO_LENGTH = 4
        const val TITLE_WEIGHT = 0.70f
        const val ARTIST_WEIGHT = 0.30f
        const val INITIAL_VERBOSE_COMPARISONS = 20L
        const val COMPARISON_LOG_INTERVAL = 100L
        const val DEBUG_TEXT_LIMIT = 48
        val comparisonCounter = AtomicLong()
        val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
        val MULTIPLE_SPACES = Regex("\\s+")
    }
}
