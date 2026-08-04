package com.yellastrodev.dwij.data.source

import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.toDataError
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.network.YamResult
import com.yellastrodev.yandexmusiclib.search.SearchResponse
import com.yellastrodev.yandexmusiclib.search.SearchType

/** Выполняет общий поиск Яндекс Музыки без ограничения категории. */
class SearchRemoteSource(
    private val client: YamApiClient,
) {
    /** Запрашивает одновременно все поддерживаемые API категории результатов. */
    suspend fun searchAll(query: String): DataResult<SearchResponse> =
        when (
            val result = client.search(
                text = query,
                type = SearchType.ALL,
            )
        ) {
            is YamResult.Success -> DataResult.Success(result.value)
            is YamResult.Failure -> DataResult.Failure(result.error.toDataError())
        }
}
