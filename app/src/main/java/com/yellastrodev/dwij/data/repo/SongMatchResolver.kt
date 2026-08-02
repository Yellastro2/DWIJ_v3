package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.entities.SONG_ARTIST_SEPARATOR
import com.yellastrodev.dwij.data.entities.SongEntity
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

data class SongMatchScore(
    val titleSimilarity: Float,
    val artistSimilarity: Float,
    val total: Float,
)

/** Чистый fuzzy-поиск: сравнивает только название и исполнителей, не объединяя песни. */
class SongMatchResolver {
    fun compare(first: SongEntity, second: SongEntity): SongMatchScore? {
        val firstTitle = normalize(first.title)
        val secondTitle = normalize(second.title)
        val firstArtists = normalizeArtists(first.artistNames)
        val secondArtists = normalizeArtists(second.artistNames)
        if (
            firstTitle.isBlank() || secondTitle.isBlank() ||
            firstArtists.isBlank() || secondArtists.isBlank()
        ) {
            return null
        }

        val titleDistance = levenshteinDistance(firstTitle, secondTitle)
        val titleSimilarity = similarity(firstTitle, secondTitle, titleDistance)
        if (!isWithinTolerance(firstTitle, secondTitle, titleDistance, titleSimilarity, MIN_TITLE_SIMILARITY)) {
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
            return null
        }
        val total = titleSimilarity * TITLE_WEIGHT + artistSimilarity * ARTIST_WEIGHT
        return SongMatchScore(
            titleSimilarity = titleSimilarity,
            artistSimilarity = artistSimilarity,
            total = total,
        )
    }

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
        const val MIN_TITLE_SIMILARITY = 0.88f
        const val MIN_ARTIST_SIMILARITY = 0.88f
        const val MAX_SINGLE_TYPO = 1
        const val MIN_SINGLE_TYPO_LENGTH = 4
        const val TITLE_WEIGHT = 0.70f
        const val ARTIST_WEIGHT = 0.30f
        val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
        val MULTIPLE_SPACES = Regex("\\s+")
    }
}
