package com.yellastrodev.yandexmusiclib.tracks

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

class TrackApiTest {

    @Test
    fun tracksUsesPythonBatchPayloadAndDecodesList() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(
                YamHttpResponse(
                    200,
                    """
                    {
                      "result":[{
                        "id":"10",
                        "title":"Track",
                        "available":true,
                        "artists":[],
                        "albums":[]
                      }]
                    }
                    """.trimIndent()
                )
            )
        )

        val result = TrackApi(transport).tracks(listOf("10", "20"))

        assertTrue(result is YamResult.Success)
        assertEquals("10", (result as YamResult.Success).value.single().id)
        assertEquals(YamHttpMethod.POST, transport.lastRequest?.method)
        assertEquals("/tracks", transport.lastRequest?.path)
        assertEquals(
            YamHttpBody.Form(
                mapOf(
                    "track-ids" to "10,20",
                    "with-positions" to "True"
                )
            ),
            transport.lastRequest?.body
        )
    }

    @Test
    fun malformedTrackReturnsInvalidResponse() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(
                YamHttpResponse(200, """{"result":[{"id":"10"}]}""")
            )
        )

        val result = TrackApi(transport).tracks(listOf("10"))

        assertTrue(result is YamResult.Failure)
        assertTrue((result as YamResult.Failure).error is YamError.InvalidResponse)
    }

    @Test
    fun emptyIdsFailWithoutRequest() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(YamHttpResponse(200, """{"result":[]}"""))
        )

        val result = TrackApi(transport).tracks(emptyList())

        assertTrue(result is YamResult.Failure)
        assertNull(transport.lastRequest)
    }

    @Test
    fun playAudioUsesPythonFormAndDecodesOk() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(
                YamHttpResponse(200, """{"result":"ok"}""")
            )
        )
        val request = PlayAudioRequest(
            trackId = "10",
            source = "dwij-android",
            albumId = "20",
            playlistId = "30:40",
            fromCache = true,
            playId = "play-1",
            uid = 50,
            timestamp = "2026-07-31T10:00:00Z",
            trackLengthSeconds = 180,
            totalPlayedSeconds = 42.5,
            endPositionSeconds = 43.25,
            clientNow = "2026-07-31T10:00:01Z"
        )

        val result = TrackApi(transport).playAudio(request)

        assertTrue(result is YamResult.Success)
        assertEquals(Unit, (result as YamResult.Success).value)
        assertEquals(YamHttpMethod.POST, transport.lastRequest?.method)
        assertEquals("/play-audio", transport.lastRequest?.path)
        assertEquals(
            YamHttpBody.Form(
                mapOf(
                    "track-id" to "10",
                    "from-cache" to "True",
                    "from" to "dwij-android",
                    "play-id" to "play-1",
                    "uid" to "50",
                    "timestamp" to "2026-07-31T10:00:00Z",
                    "track-length-seconds" to "180",
                    "total-played-seconds" to "42.5",
                    "end-position-seconds" to "43.25",
                    "album-id" to "20",
                    "playlist-id" to "30:40",
                    "client-now" to "2026-07-31T10:00:01Z"
                )
            ),
            transport.lastRequest?.body
        )
    }

    @Test
    fun invalidPlayAudioFailsWithoutRequest() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(
                YamHttpResponse(200, """{"result":"ok"}""")
            )
        )

        val result = TrackApi(transport).playAudio(
            PlayAudioRequest(
                trackId = "",
                source = "dwij-android",
                albumId = "20",
                playId = "play-invalid"
            )
        )

        assertTrue(result is YamResult.Failure)
        assertTrue((result as YamResult.Failure).error is YamError.InvalidResponse)
        assertNull(transport.lastRequest)
    }

    @Test
    fun playAudioWithoutPlaylistOmitsPlaylistField() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(
                YamHttpResponse(200, """{"result":"ok"}""")
            )
        )

        val result = TrackApi(
            transport = transport,
            currentIsoTimestamp = { "2026-07-31T12:00:00Z" }
        ).playAudio(
            PlayAudioRequest(
                trackId = "10",
                source = "dwij-android",
                albumId = "20",
                playId = "play-2"
            )
        )

        assertTrue(result is YamResult.Success)
        val fields = (transport.lastRequest?.body as YamHttpBody.Form).fields
        assertTrue("playlist-id" !in fields)
        assertTrue("uid" !in fields)
        assertEquals("play-2", fields["play-id"])
        assertEquals("2026-07-31T12:00:00Z", fields["timestamp"])
        assertEquals("2026-07-31T12:00:00Z", fields["client-now"])
    }

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
