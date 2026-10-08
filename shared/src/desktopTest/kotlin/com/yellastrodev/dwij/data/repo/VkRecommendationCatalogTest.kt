package com.yellastrodev.dwij.data.repo

import com.sun.net.httpserver.HttpServer
import com.yellastrodev.vkmusicsdk.VkApiClient
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Проверяет ленивые запросы, нулевой count метаданных через локальный HTTP. */
class VkRecommendationCatalogTest {
    /** Открытие каталога запрашивает только метаданные, не состав generated-плейлиста с count=0. */
    @Test
    fun catalogDoesNotPreloadGeneratedTracks() = runBlocking {
        withApi(listOf(CATALOG)) { client, calls ->
            val cards = VkRecommendationCatalog(client).cards()
            assertEquals(listOf("Для вас"), cards.map { it.title })
            assertEquals(listOf("catalog.getAudio"), calls.map { it.first })
        }
    }

    /** По нажатию count=0 не мешает audio.get, а полученный access_key передаётся в запрос состава. */
    @Test
    fun generatedPlaylistLoadsTracksOnlyWhenSelected() = runBlocking {
        withApi(listOf(CATALOG, """{"count":1,"items":[{"id":1,"owner_id":20,"title":"Трек"}]}""")) { client, calls ->
            val content = VkRecommendationCatalog(client).content("vkrec:generated:-21")
            assertEquals(1, content.tracks.size)
            assertFalse(content.playlist.canEdit(20))
            assertEquals(listOf("catalog.getAudio", "audio.get"), calls.map { it.first })
            assertEquals("-21", calls[1].second["album_id"])
            assertEquals("fixture-key", calls[1].second["access_key"])
        }
    }

    /** Разворачивает ограниченную последовательность API-ответов без обращения к VK. */
    private suspend fun withApi(
        responses: List<String>,
        block: suspend (VkApiClient, MutableList<Pair<String, Map<String, String>>>) -> Unit,
    ) {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/method/") { exchange ->
            val form = exchange.requestBody.bufferedReader().use { it.readText() }.split('&').associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            val index = calls.size
            calls.add(exchange.requestURI.path.substringAfterLast('/') to form)
            val response = if (index < responses.size) "{\"response\":${responses[index]}}"
                else """{"error":{"error_code":100}}"""
            val bytes = response.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val client = VkApiClient("fixture-token", apiBase = "http://127.0.0.1:${server.address.port}/method/")
        try { block(client, calls) } finally { client.close(); server.stop(0) }
    }

    companion object {
        private const val CATALOG = """{"catalog":{"sections":[]},"playlists":[{"id":-21,"owner_id":20,"title":"Для вас","count":0,"access_key":"fixture-key"}]}"""
    }
}
