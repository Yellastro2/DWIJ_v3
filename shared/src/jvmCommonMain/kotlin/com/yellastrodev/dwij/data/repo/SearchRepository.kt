package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.source.SearchRemoteSource
import com.yellastrodev.yandexmusiclib.YamLogger
import com.yellastrodev.yandexmusiclib.search.SearchResponse

/** Репозиторий общего поиска и диагностической сводки декодированного ответа. */
class SearchRepository(
    private val remote: SearchRemoteSource,
    private val logger: YamLogger
) {
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

    private companion object {
        const val TAG = "SearchRepository"
    }
}