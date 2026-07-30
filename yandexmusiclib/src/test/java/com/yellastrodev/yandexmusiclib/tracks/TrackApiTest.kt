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
