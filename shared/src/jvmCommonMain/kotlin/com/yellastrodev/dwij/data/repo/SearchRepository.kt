package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.map
import com.yellastrodev.dwij.data.source.SearchRemoteSource
import com.yellastrodev.yamusicsdk.YamLogger
import com.yellastrodev.yamusicsdk.entities.YaArtist
import com.yellastrodev.yamusicsdk.entities.YaTrack
import com.yellastrodev.yamusicsdk.search.SearchResponse
import com.yellastrodev.yamusicsdk.search.SearchType

/** Репозиторий общего поиска и диагностической сводки декодированного ответа. */
class SearchRepository(
    private val remote: SearchRemoteSource,
    private val logger: YamLogger
) : LocalCatalogSearch {
    /** Возвращает общий ответ поиска и пишет краткое число декодированных сущностей. */
    suspend fun searchAll(query: String): DataResult<SearchResponse> {
        logger.debug(TAG, "[searchAll] Запрос=\"$query\", категория=ALL")
        val result = remote.searchAll(query)
        if (result is DataResult.Success) {
            val response = result.value
            logger.debug(
                TAG,
                "[searchAll] Получено: треки=${response.tracks?.results?.size ?: 0}/" +
                    "${response.tracks?.total ?: 0}, альбомы=" +
                    "${response.albums?.results?.size ?: 0}/${response.albums?.total ?: 0}, " +
                    "артисты=${response.artists?.results?.size ?: 0}/" +
                    "${response.artists?.total ?: 0}",
            )
        } else if (result is DataResult.Failure) {
            logger.warning(TAG, "[searchAll] Поиск не выполнен: ${result.error}")
        }
        return result
    }

    /** Ищет только треки и не сохраняет результаты выдачи в Room. */
    override suspend fun searchTracks(query: String): DataResult<List<YaTrack>> =
        searchSection(query, SearchType.TRACK) { response ->
            response.tracks?.results.orEmpty()
        }

    /** Ищет только артистов и не сохраняет результаты выдачи в Room. */
    override suspend fun searchArtists(query: String): DataResult<List<YaArtist>> =
        searchSection(query, SearchType.ARTIST) { response ->
            response.artists?.results.orEmpty()
        }

    private suspend fun <T> searchSection(
        query: String,
        type: SearchType,
        extract: (SearchResponse) -> List<T>,
    ): DataResult<List<T>> {
        logger.debug(TAG, "[searchSection] Запрос=\"$query\", категория=$type")
        return remote.search(query, type).map(extract)
    }

    private companion object {
        const val TAG = "SearchRepository"
    }
}
