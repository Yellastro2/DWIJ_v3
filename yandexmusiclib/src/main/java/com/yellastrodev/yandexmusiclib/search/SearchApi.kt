package com.yellastrodev.yandexmusiclib.search

import com.yellastrodev.yandexmusiclib.network.YamError
import com.yellastrodev.yandexmusiclib.network.YamHttpMethod
import com.yellastrodev.yandexmusiclib.network.YamHttpRequest
import com.yellastrodev.yandexmusiclib.network.YamResponseDecoder
import com.yellastrodev.yandexmusiclib.network.YamResult
import com.yellastrodev.yandexmusiclib.network.YamTransport

/** Внутренний API-срез поиска и поисковых подсказок Яндекс Музыки. */
internal class SearchApi(
    private val transport: YamTransport,
) {

    /** Выполняет поиск с параметрами, совместимыми с Python SDK. */
    suspend fun search(
        text: String,
        nocorrect: Boolean,
        type: SearchType,
        page: Int,
        playlistInBest: Boolean,
    ): YamResult<SearchResponse> {
        if (text.isBlank() || page < 0) {
            return invalidArguments("Текст поиска не должен быть пустым, page не может быть отрицательной")
        }

        return when (
            val response = transport.execute(
                YamHttpRequest(
                    method = YamHttpMethod.GET,
                    path = "/search",
                    query = mapOf(
                        "text" to text,
                        "nocorrect" to nocorrect.toPythonBoolean(),
                        "type" to type.apiValue,
                        "page" to page.toString(),
                        "playlist-in-best" to playlistInBest.toPythonBoolean(),
                    ),
                ),
            )
        ) {
            is YamResult.Success -> YamResponseDecoder.decodeResult(
                response.value,
                SearchResponse.serializer(),
            )
            is YamResult.Failure -> response
        }
    }

    /** Запрашивает поисковые подсказки для введённой части запроса. */
    suspend fun suggestions(part: String): YamResult<SearchSuggestions> {
        if (part.isBlank()) {
            return invalidArguments("Часть поискового запроса не должна быть пустой")
        }

        return when (
            val response = transport.execute(
                YamHttpRequest(
                    method = YamHttpMethod.GET,
                    path = "/search/suggest",
                    query = mapOf("part" to part),
                ),
            )
        ) {
            is YamResult.Success -> YamResponseDecoder.decodeResult(
                response.value,
                SearchSuggestions.serializer(),
            )
            is YamResult.Failure -> response
        }
    }

    /** Приводит булево значение к формату query-параметров Python SDK. */
    private fun Boolean.toPythonBoolean(): String = if (this) "True" else "False"

    /** Возвращает единообразную ошибку для некорректных аргументов поиска. */
    private fun <T> invalidArguments(message: String): YamResult<T> =
        YamResult.Failure(
            YamError.InvalidResponse(IllegalArgumentException(message)),
        )
}
