package com.yellastrodev.dwij.data.source

import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.toDataError
import com.yellastrodev.yamusicsdk.YamApiClient
import com.yellastrodev.yamusicsdk.network.YamResult
import com.yellastrodev.yamusicsdk.search.SearchResponse
import com.yellastrodev.yamusicsdk.search.SearchType

/** Выполняет общий поиск Яндекс Музыки без ограничения категории. */
class SearchRemoteSource(
    private val client: YamApiClient,
) {
    /** Запрашивает одновременно все поддерживаемые API категории результатов. */
    suspend fun searchAll(query: String): DataResult<SearchResponse> =
        search(query = query, type = SearchType.ALL)

    /** Запрашивает одну категорию, чтобы фоновые потребители не загружали лишние разделы. */
    suspend fun search(
        query: String,
        type: SearchType,
    ): DataResult<SearchResponse> =
        when (
            val result = client.search(
                text = query,
                type = type,
            )
        ) {
            is YamResult.Success -> DataResult.Success(result.value)
            is YamResult.Failure -> DataResult.Failure(result.error.toDataError())
        }
}
