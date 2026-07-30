package com.yellastrodev.yandexmusiclib.playlists

import com.yellastrodev.yandexmusiclib.network.YamError
import com.yellastrodev.yandexmusiclib.network.YamHttpBody
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

class PlaylistApiTest {

    @Test
    fun playlistDecodesMetadataAndTracksFromOneResult() = runBlocking {
        val transport = FakeTransport(successResponse(playlistJson()))

        val result = PlaylistApi(transport).playlist("100", "7")

        assertTrue(result is YamResult.Success)
        val details = (result as YamResult.Success).value
        assertEquals("Test", details.playlist.title)
        assertTrue(details.tracks.isEmpty())
        assertEquals(YamHttpMethod.GET, transport.lastRequest?.method)
        assertEquals("/users/100/playlists/7", transport.lastRequest?.path)
    }

    @Test
    fun playlistsDecodesResultList() = runBlocking {
        val transport = FakeTransport(successResponse("[${playlistJson()}]"))

        val result = PlaylistApi(transport).playlists("100")

        assertTrue(result is YamResult.Success)
        assertEquals(1, (result as YamResult.Success).value.size)
        assertEquals("/users/100/playlists/list", transport.lastRequest?.path)
    }

    @Test
    fun createUsesFormAndReturnsPlaylist() = runBlocking {
        val transport = FakeTransport(successResponse(playlistJson()))

        val result = PlaylistApi(transport).create(
            userId = "100",
            title = "Test",
            visibility = PlaylistVisibility.PRIVATE
        )

        assertTrue(result is YamResult.Success)
        assertEquals(
            YamHttpBody.Form(
                mapOf("title" to "Test", "visibility" to "private")
            ),
            transport.lastRequest?.body
        )
    }

    @Test
    fun insertTrackUsesPythonDifferenceShape() = runBlocking {
        val transport = FakeTransport(successResponse(playlistJson()))

        PlaylistApi(transport).insertTrack(
            userId = "100",
            kind = "7",
            revision = 3,
            trackId = "20",
            albumId = "30",
            at = 0
        )

        assertEquals(
            YamHttpBody.Form(
                mapOf(
                    "kind" to "7",
                    "revision" to "3",
                    "diff" to
                        """[{"op":"insert","at":0,"tracks":[{"id":"20","albumId":"30"}]}]"""
                )
            ),
            transport.lastRequest?.body
        )
    }

    @Test
    fun deleteRejectsUnexpectedResult() = runBlocking {
        val transport = FakeTransport(successResponse("false"))

        val result = PlaylistApi(transport).delete("100", "7")

        assertTrue(result is YamResult.Failure)
        assertTrue((result as YamResult.Failure).error is YamError.InvalidResponse)
    }

    @Test
    fun blankUserFailsWithoutRequest() = runBlocking {
        val transport = FakeTransport(successResponse("[]"))

        val result = PlaylistApi(transport).playlists("")

        assertTrue(result is YamResult.Failure)
        assertNull(transport.lastRequest)
    }

    private fun successResponse(result: String): YamResult<YamHttpResponse> =
        YamResult.Success(
            YamHttpResponse(200, """{"result":$result}""")
        )

    private fun playlistJson(): String =
        """
        {
          "playlistUuid":"uuid-7",
          "uid":100,
          "kind":7,
          "title":"Test",
          "trackCount":0,
          "revision":3,
          "snapshot":1,
          "visibility":"private",
          "collective":false,
          "isBanner":false,
          "isPremiere":false,
          "tracks":[]
        }
        """.trimIndent()

    private class FakeTransport(
        private val result: YamResult<YamHttpResponse>
    ) : YamTransport {
        var lastRequest: YamHttpRequest? = null

        override suspend fun execute(
            request: YamHttpRequest
        ): YamResult<YamHttpResponse> {
            lastRequest = request
            return result
        }
    }
}
