package com.yellastrodev.yandexmusiclib.search

import com.yellastrodev.yandexmusiclib.network.YamError
import com.yellastrodev.yandexmusiclib.network.YamHttpMethod
import com.yellastrodev.yandexmusiclib.network.YamHttpRequest
import com.yellastrodev.yandexmusiclib.network.YamHttpResponse
import com.yellastrodev.yandexmusiclib.network.YamResult
import com.yellastrodev.yandexmusiclib.network.YamTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Проверяет HTTP-контракты и декодирование поиска без сетевых запросов. */
class SearchApiTest {

    /** Проверяет `/search`, его query-параметры и типизированные разделы ответа. */
    @Test
    fun searchUsesPythonQueryAndDecodesKnownAndCompactSections() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(
                YamHttpResponse(
                    200,
                    """
                    {
                      "result": {
                        "searchRequestId": "request-1",
                        "text": "NCS",
                        "best": {
                          "type": "track",
                          "text": "NCS",
                          "result": {"id":"10","title":"Track","available":true,"artists":[],"albums":[]}
                        },
                        "tracks": {
                          "total": 1,
                          "perPage": 20,
                          "order": 1,
                          "results": [{"id":"10","title":"Track","available":true,"artists":[],"albums":[]}]
                        },
                        "artists": {
                          "total": 1,
                          "perPage": 20,
                          "order": 2,
                          "results": [{"id":7,"name":"Artist"}]
                        },
                        "albums": {
                          "total": 1,
                          "perPage": 20,
                          "order": 3,
                          "results": [{"id":11,"title":"Album"}]
                        },
                        "videos": {
                          "total": 1,
                          "perPage": 20,
                          "order": 4,
                          "results": [{"id":"video-1","title":"Video"}]
                        },
                        "type": "all",
                        "page": 0,
                        "perPage": 20,
                        "misspellCorrected": false,
                        "nocorrect": false
                      }
                    }
                    """.trimIndent(),
                ),
            ),
        )

        val result = SearchApi(transport).search(
            text = "NCS",
            nocorrect = false,
            type = SearchType.ALL,
            page = 0,
            playlistInBest = true,
        )

        assertTrue(result is YamResult.Success)
        val response = (result as YamResult.Success).value
        assertEquals("request-1", response.searchRequestId)
        assertEquals("10", response.tracks?.results?.single()?.id)
        assertEquals("Artist", response.artists?.results?.single()?.name)
        assertEquals("Album", response.albums?.results?.single()?.title)
        assertEquals("video-1", response.videos?.results?.single()?.id)
        assertEquals(SearchType.TRACK, response.best?.type)
        assertEquals("10", response.best?.result?.id)
        assertEquals(
            mapOf(
                "text" to "NCS",
                "nocorrect" to "False",
                "type" to "all",
                "page" to "0",
                "playlist-in-best" to "True",
            ),
            transport.lastRequest?.query,
        )
        assertEquals(YamHttpMethod.GET, transport.lastRequest?.method)
        assertEquals("/search", transport.lastRequest?.path)
    }

    /** Проверяет `/search/suggest` и декодирование поисковых подсказок. */
    @Test
    fun searchSuggestionsUsesSuggestEndpointAndDecodesResponse() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(
                YamHttpResponse(
                    200,
                    """
                    {
                      "result": {
                        "best": {
                          "type": "artist",
                          "result": {"id":7,"name":"Artist"}
                        },
                        "suggestions": ["artist", "artist live"]
                      }
                    }
                    """.trimIndent(),
                ),
            ),
        )

        val result = SearchApi(transport).suggestions("arti")

        assertTrue(result is YamResult.Success)
        val suggestions = (result as YamResult.Success).value
        assertEquals(listOf("artist", "artist live"), suggestions.suggestions)
        assertEquals(SearchType.ARTIST, suggestions.best?.type)
        assertEquals(mapOf("part" to "arti"), transport.lastRequest?.query)
        assertEquals(YamHttpMethod.GET, transport.lastRequest?.method)
        assertEquals("/search/suggest", transport.lastRequest?.path)
    }

    /** Проверяет отклонение пустого запроса и отрицательной страницы до вызова transport. */
    @Test
    fun invalidSearchArgumentsFailWithoutRequest() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(YamHttpResponse(200, """{"result":{}}""")),
        )

        val result = SearchApi(transport).search(
            text = " ",
            nocorrect = false,
            type = SearchType.ALL,
            page = -1,
            playlistInBest = true,
        )

        assertTrue(result is YamResult.Failure)
        assertTrue((result as YamResult.Failure).error is YamError.InvalidResponse)
        assertNull(transport.lastRequest)
    }

    /** Сохраняет единственный запрос API и возвращает заранее заданный ответ. */
    private class FakeTransport(
        private val result: YamResult<YamHttpResponse>,
    ) : YamTransport {
        var lastRequest: YamHttpRequest? = null

        /** Запоминает запрос для проверки его HTTP-контракта. */
        override suspend fun execute(request: YamHttpRequest): YamResult<YamHttpResponse> {
            lastRequest = request
            return result
        }
    }
}
