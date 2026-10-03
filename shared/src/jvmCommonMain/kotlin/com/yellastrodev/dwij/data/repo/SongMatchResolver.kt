package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.entities.SONG_ARTIST_SEPARATOR
import com.yellastrodev.dwij.data.entities.SongEntity
import com.yellastrodev.yamusicsdk.YamLogger
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/** Сходство названия и артистов для неподтверждённой пары песен. */
data class SongMatchScore(
    val titleSimilarity: Float,
    val artistSimilarity: Float,
    val total: Float,
)

/** Ищет кандидатов по названию и артистам; для VK дополнительно проверяет известную длительность и версию. */
class SongMatchResolver(
    private val logger: YamLogger
) {
    /** Возвращает подсказку, а не доказательство идентичности; прежний режим ЯМ ↔ local сохраняется по умолчанию. */
    fun compare(
        first: SongEntity,
        second: SongEntity,
        checkRecording: Boolean = false,
    ): SongMatchScore? {
        val comparisonNumber = comparisonCounter.incrementAndGet()
        if (checkRecording && !isCompatibleRecording(first, second)) {
            logRejected(comparisonNumber, first, second) { "разная длительность или обозначение версии" }
            return null
        }
        val firstTitle = normalize(first.title)
        val secondTitle = normalize(second.title)
        val firstArtists = normalizeArtists(first.artistNames, splitCredits = checkRecording)
        val secondArtists = normalizeArtists(second.artistNames, splitCredits = checkRecording)
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
        logger.debug(
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

    /** Неизвестная длительность не означает несовпадение; известные версии и заметно разные записи исключаются. */
    private fun isCompatibleRecording(first: SongEntity, second: SongEntity): Boolean {
        val firstDuration = first.durationMs?.takeIf { it > 0 }
        val secondDuration = second.durationMs?.takeIf { it > 0 }
        if (firstDuration != null && secondDuration != null &&
            max(firstDuration, secondDuration) - minOf(firstDuration, secondDuration) > MAX_DURATION_DIFFERENCE_MS
        ) return false
        return recordingMarkers(first.title) == recordingMarkers(second.title)
    }

    /** Проверяет целые слова, сохраняя пометки live/remix и другие варианты вместо удаления суффиксов. */
    private fun recordingMarkers(title: String): Set<Int> {
        val normalized = normalize(title)
        return RECORDING_MARKERS.indices.filterTo(mutableSetOf()) { index ->
            RECORDING_MARKERS[index].containsMatchIn(normalized)
        }
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
        logger.debug(
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

    /** Для VK сопоставляет старую строку artist с массивом артистов; режим ЯМ ↔ local оставляет прежним. */
    private fun normalizeArtists(value: String, splitCredits: Boolean): String = value
        .split(SONG_ARTIST_SEPARATOR)
        .flatMap { if (splitCredits) it.split(ARTIST_CREDIT_DIVIDERS) else listOf(it) }
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
        const val MAX_DURATION_DIFFERENCE_MS = 10_000L
        val ARTIST_CREDIT_DIVIDERS = Regex(
            "(?iu)\\s+(?:feat(?:uring)?|ft)\\.?\\s+|,\\s*|\\s+(?:x|vs\\.?|и|&)\\s+",
        )
        val RECORDING_MARKERS = listOf(
            "live|concert|лайв|концерт|концертная|концертный",
            "remix|ремикс|mix|микс",
            "acoustic|акустика|акустическая|акустический|unplugged",
            "instrumental|инструментал|инструментальная",
            "karaoke|караоке",
            "cover|кавер",
            "sped up|speed up|speedup|ускоренная|ускоренный",
            "slowed|замедленная|замедленный",
            "radio edit|radio version",
            "extended",
            "remaster|remastered|ремастер",
        ).map { Regex("(?<![\\p{L}\\p{N}])(?:$it)(?![\\p{L}\\p{N}])") }
        val comparisonCounter = AtomicLong()
        val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
        val MULTIPLE_SPACES = Regex("\\s+")
    }
}
