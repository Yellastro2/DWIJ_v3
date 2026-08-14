package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.LocalTrackEntity
import com.yellastrodev.yamusicsdk.entities.YaArtist
import com.yellastrodev.yamusicsdk.entities.YaTrack
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

/** Минимальная поисковая граница, позволяющая тестировать резолвер без сетевого клиента. */
interface LocalCatalogSearch {
    suspend fun searchTracks(query: String): DataResult<List<YaTrack>>

    suspend fun searchArtists(query: String): DataResult<List<YaArtist>>
}

/** Этап, на котором резолвер принял решение или обнаружил неоднозначность. */
enum class LocalCatalogResolutionStage {
    TRACK,
    FULL_ARTIST,
    SPLIT_ARTISTS,
}

/** Оценка близости исходной локальной метадаты и результата поиска. */
data class LocalCatalogMatchScore(
    val titleSimilarity: Float?,
    val artistSimilarity: Float,
    val total: Float,
)

/** Краткий кандидат для диагностики неоднозначной выдачи. */
data class LocalCatalogCandidate(
    val externalId: String,
    val title: String,
    val artistNames: List<String>,
    val score: LocalCatalogMatchScore,
)

/** Результат чистого поиска: сам по себе он ничего не записывает и не объединяет. */
sealed interface LocalCatalogResolution {
    data class Track(
        val track: YaTrack,
        val score: LocalCatalogMatchScore,
    ) : LocalCatalogResolution

    data class Artists(
        val artists: List<YaArtist>,
        val scores: List<LocalCatalogMatchScore>,
        val stage: LocalCatalogResolutionStage,
    ) : LocalCatalogResolution

    data class NotFound(
        val lastStage: LocalCatalogResolutionStage,
    ) : LocalCatalogResolution

    data class Ambiguous(
        val stage: LocalCatalogResolutionStage,
        val candidates: List<LocalCatalogCandidate>,
    ) : LocalCatalogResolution
}

/**
 * Сопоставляет один локальный файл с каталогом Яндекс Музыки.
 *
 * Последовательно ищет точный трек, артиста по полной исходной строке и только затем
 * отдельных артистов после осторожного разделения строки. Резолвер не изменяет Room:
 * вызывающий код позднее сможет применить подтверждённый результат одной транзакцией.
 */
class LocalCatalogResolver(
    private val search: LocalCatalogSearch,
) {
    suspend fun resolve(localTrack: LocalTrackEntity): DataResult<LocalCatalogResolution> {
        val title = localTrack.title.trim()
        val artistCredit = localTrack.artist?.trim().orEmpty()
        if (title.isBlank() || artistCredit.isBlank()) {
            return DataResult.Success(
                LocalCatalogResolution.NotFound(LocalCatalogResolutionStage.TRACK),
            )
        }

        when (val tracksResult = search.searchTracks("$artistCredit $title")) {
            is DataResult.Failure -> return tracksResult
            is DataResult.Success -> when (
                val selection = selectTrack(
                    localTitle = title,
                    localArtistCredit = artistCredit,
                    candidates = tracksResult.value,
                )
            ) {
                is CandidateSelection.Match -> return DataResult.Success(
                    LocalCatalogResolution.Track(selection.value, selection.score),
                )
                is CandidateSelection.Ambiguous -> return DataResult.Success(
                    LocalCatalogResolution.Ambiguous(
                        stage = LocalCatalogResolutionStage.TRACK,
                        candidates = selection.candidates,
                    ),
                )
                CandidateSelection.None -> Unit
            }
        }

        when (val artistsResult = search.searchArtists(artistCredit)) {
            is DataResult.Failure -> return artistsResult
            is DataResult.Success -> when (
                val selection = selectArtist(artistCredit, artistsResult.value)
            ) {
                is CandidateSelection.Match -> return DataResult.Success(
                    LocalCatalogResolution.Artists(
                        artists = listOf(selection.value),
                        scores = listOf(selection.score),
                        stage = LocalCatalogResolutionStage.FULL_ARTIST,
                    ),
                )
                is CandidateSelection.Ambiguous -> return DataResult.Success(
                    LocalCatalogResolution.Ambiguous(
                        stage = LocalCatalogResolutionStage.FULL_ARTIST,
                        candidates = selection.candidates,
                    ),
                )
                CandidateSelection.None -> Unit
            }
        }

        val artistParts = splitArtistCredit(artistCredit)
        if (artistParts.size < 2) {
            return DataResult.Success(
                LocalCatalogResolution.NotFound(LocalCatalogResolutionStage.FULL_ARTIST),
            )
        }

        val matchedArtists = mutableListOf<YaArtist>()
        val scores = mutableListOf<LocalCatalogMatchScore>()
        for (artistPart in artistParts) {
            when (val artistsResult = search.searchArtists(artistPart)) {
                is DataResult.Failure -> return artistsResult
                is DataResult.Success -> when (
                    val selection = selectArtist(artistPart, artistsResult.value)
                ) {
                    is CandidateSelection.Match -> {
                        matchedArtists += selection.value
                        scores += selection.score
                    }
                    is CandidateSelection.Ambiguous -> return DataResult.Success(
                        LocalCatalogResolution.Ambiguous(
                            stage = LocalCatalogResolutionStage.SPLIT_ARTISTS,
                            candidates = selection.candidates,
                        ),
                    )
                    CandidateSelection.None -> return DataResult.Success(
                        LocalCatalogResolution.NotFound(
                            LocalCatalogResolutionStage.SPLIT_ARTISTS,
                        ),
                    )
                }
            }
        }

        val distinctMatches = matchedArtists
            .zip(scores)
            .distinctBy { (artist, _) -> artist.id?.toString() ?: normalize(artist.name) }
        return DataResult.Success(
            LocalCatalogResolution.Artists(
                artists = distinctMatches.map { it.first },
                scores = distinctMatches.map { it.second },
                stage = LocalCatalogResolutionStage.SPLIT_ARTISTS,
            ),
        )
    }

    private fun selectTrack(
        localTitle: String,
        localArtistCredit: String,
        candidates: List<YaTrack>,
    ): CandidateSelection<YaTrack> {
        val normalizedTitle = normalize(localTitle)
        val normalizedArtists = normalizeArtistCredit(localArtistCredit)
        val accepted = candidates.distinctBy(YaTrack::id).mapNotNull { candidate ->
            val titleMatch = compareNormalized(normalizedTitle, normalize(candidate.title))
            val artistMatch = compareNormalized(
                normalizedArtists,
                normalizeArtistCredit(candidate.artists.joinToString(" ") { it.name }),
            )
            if (!titleMatch.accepted || !artistMatch.accepted) return@mapNotNull null
            val score = LocalCatalogMatchScore(
                titleSimilarity = titleMatch.similarity,
                artistSimilarity = artistMatch.similarity,
                total = titleMatch.similarity * TITLE_WEIGHT +
                    artistMatch.similarity * ARTIST_WEIGHT,
            )
            ScoredValue(
                value = candidate,
                score = score,
                candidate = LocalCatalogCandidate(
                    externalId = candidate.id,
                    title = candidate.title,
                    artistNames = candidate.artists.map { it.name },
                    score = score,
                ),
            )
        }
        return choose(accepted)
    }

    private fun selectArtist(
        localName: String,
        candidates: List<YaArtist>,
    ): CandidateSelection<YaArtist> {
        val normalizedName = normalize(localName)
        val accepted = candidates
            .filter { it.id != null }
            .distinctBy { it.id }
            .mapNotNull { candidate ->
                val match = compareNormalized(normalizedName, normalize(candidate.name))
                if (!match.accepted) return@mapNotNull null
                val score = LocalCatalogMatchScore(
                    titleSimilarity = null,
                    artistSimilarity = match.similarity,
                    total = match.similarity,
                )
                ScoredValue(
                    value = candidate,
                    score = score,
                    candidate = LocalCatalogCandidate(
                        externalId = requireNotNull(candidate.id).toString(),
                        title = candidate.name,
                        artistNames = emptyList(),
                        score = score,
                    ),
                )
            }
        return choose(accepted)
    }

    private fun <T> choose(values: List<ScoredValue<T>>): CandidateSelection<T> {
        val sorted = values.sortedByDescending { it.score.total }
        val best = sorted.firstOrNull() ?: return CandidateSelection.None
        val second = sorted.getOrNull(1)
        if (second != null && best.score.total - second.score.total < MIN_WINNER_MARGIN) {
            return CandidateSelection.Ambiguous(sorted.take(AMBIGUOUS_CANDIDATE_LIMIT).map {
                it.candidate
            })
        }
        return CandidateSelection.Match(best.value, best.score)
    }

    private fun compareNormalized(first: String, second: String): TextMatch {
        if (first.isBlank() || second.isBlank()) return TextMatch(0f, accepted = false)
        if (first == second) return TextMatch(1f, accepted = true)
        val distance = levenshteinDistance(first, second)
        val longest = max(first.length, second.length)
        val similarity = 1f - distance.toFloat() / longest
        val maxDistance = when {
            longest < 5 -> 0
            longest < 10 -> 1
            else -> 2
        }
        return TextMatch(
            similarity = similarity,
            accepted = distance <= maxDistance && similarity >= MIN_TEXT_SIMILARITY,
        )
    }

    private fun splitArtistCredit(value: String): List<String> = value
        .split(ARTIST_DIVIDERS)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy(::normalize)

    private fun normalizeArtistCredit(value: String): String = normalize(
        value.replace(ARTIST_DIVIDERS, " "),
    )

    private fun normalize(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .replace(NON_ALPHANUMERIC, " ")
        .trim()
        .replace(MULTIPLE_SPACES, " ")

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

    private data class TextMatch(
        val similarity: Float,
        val accepted: Boolean,
    )

    private data class ScoredValue<T>(
        val value: T,
        val score: LocalCatalogMatchScore,
        val candidate: LocalCatalogCandidate,
    )

    private sealed interface CandidateSelection<out T> {
        data class Match<T>(
            val value: T,
            val score: LocalCatalogMatchScore,
        ) : CandidateSelection<T>

        data class Ambiguous(
            val candidates: List<LocalCatalogCandidate>,
        ) : CandidateSelection<Nothing>

        data object None : CandidateSelection<Nothing>
    }

    private companion object {
        const val MIN_TEXT_SIMILARITY = 0.90f
        const val MIN_WINNER_MARGIN = 0.03f
        const val TITLE_WEIGHT = 0.70f
        const val ARTIST_WEIGHT = 0.30f
        const val AMBIGUOUS_CANDIDATE_LIMIT = 5
        val ARTIST_DIVIDERS = Regex(
            "(?iu)\\s+(?:feat(?:uring)?|ft)\\.?\\s+|,\\s*|" +
                "\\s+(?:x|vs\\.?|и)\\s+|\\s+&\\s+",
        )
        val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
        val MULTIPLE_SPACES = Regex("\\s+")
    }
}
