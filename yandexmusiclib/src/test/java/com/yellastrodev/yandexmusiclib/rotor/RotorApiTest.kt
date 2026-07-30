package com.yellastrodev.yandexmusiclib.rotor

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

class RotorApiTest {

    @Test
    fun initialTracksUsesPythonSettingsAndDecodesBatch() = runBlocking {
        val transport = FakeTransport(tracksResponse())

        val result = RotorApi(transport).tracks("user:onyourwave")

        assertTrue(result is YamResult.Success)
        val batch = (result as YamResult.Success).value
        assertEquals("batch-1", batch.batchId)
        assertEquals("10", batch.tracks.single().id)
        assertEquals(YamHttpMethod.GET, transport.lastRequest?.method)
        assertEquals(
            "/rotor/station/user:onyourwave/tracks",
            transport.lastRequest?.path
        )
        assertEquals(mapOf("settings2" to "True"), transport.lastRequest?.query)
    }

    @Test
    fun queuedTracksFollowPythonQuerySemantics() = runBlocking {
        val transport = FakeTransport(tracksResponse())

        RotorApi(transport).tracks(
            station = "user:onyourwave",
            queue = "10"
        )

        assertEquals(mapOf("queue" to "10"), transport.lastRequest?.query)
    }

    @Test
    fun feedbackUsesFormAndBatchQuery() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(YamHttpResponse(200, """{"result":"ok"}"""))
        )

        val result = RotorApi(
            transport = transport,
            timestampSeconds = { 123.5 }
        ).feedback(
            station = "user:onyourwave",
            type = RotorFeedbackType.TRACK_FINISHED,
            trackId = "10",
            totalPlayedSeconds = 42f,
            batchId = "batch-1"
        )

        assertEquals(YamResult.Success(Unit), result)
        assertEquals(
            mapOf("batch-id" to "batch-1"),
            transport.lastRequest?.query
        )
        assertEquals(
            YamHttpBody.Form(
                mapOf(
                    "type" to "trackFinished",
                    "timestamp" to "123.5",
                    "trackId" to "10",
                    "totalPlayedSeconds" to "42.0"
                )
            ),
            transport.lastRequest?.body
        )
    }

    @Test
    fun feedbackTimestampNeverUsesScientificNotation() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(YamHttpResponse(200, """{"result":"ok"}"""))
        )

        RotorApi(
            transport = transport,
            timestampSeconds = { 1_785_425_833.541 }
        ).feedback(
            station = "user:onyourwave",
            type = RotorFeedbackType.TRACK_STARTED,
            trackId = "10",
            batchId = "batch-1"
        )

        val body = transport.lastRequest?.body as YamHttpBody.Form
        assertEquals("1785425833.541", body.fields["timestamp"])
    }

    @Test
    fun radioStartedUsesPythonCompatibleFromField() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(YamHttpResponse(200, """{"result":"ok"}"""))
        )

        val result = RotorApi(
            transport = transport,
            timestampSeconds = { 1_785_425_833.0 }
        ).feedback(
            station = "user:onyourwave",
            type = RotorFeedbackType.RADIO_STARTED,
            from = "mobile-radio-user-123",
            batchId = "batch-1"
        )

        assertEquals(YamResult.Success(Unit), result)
        val body = transport.lastRequest?.body as YamHttpBody.Form
        assertEquals("mobile-radio-user-123", body.fields["from"])
        assertEquals(null, body.fields["trackId"])
    }

    @Test
    fun blankStationFailsWithoutRequest() = runBlocking {
        val transport = FakeTransport(tracksResponse())

        val result = RotorApi(transport).tracks("")

        assertTrue(result is YamResult.Failure)
        assertTrue((result as YamResult.Failure).error is YamError.InvalidResponse)
        assertNull(transport.lastRequest)
    }

    private fun tracksResponse(): YamResult<YamHttpResponse> =
        YamResult.Success(
            YamHttpResponse(
                200,
                """
                {
                  "result":{
                    "batchId":"batch-1",
                    "sequence":[{
                      "track":{
                        "id":"10",
                        "title":"Track",
                        "available":true,
                        "artists":[],
                        "albums":[]
                      }
                    }]
                  }
                }
                """.trimIndent()
            )
        )

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
