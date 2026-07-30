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
    fun feedbackSourceUsesStationIdForFrom() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(
                YamHttpResponse(
                    200,
                    """
                    {
                      "result":[{
                        "station":{
                          "id":{"type":"user","tag":"onyourwave"},
                          "idForFrom":"user-123",
                          "name":"Моя волна"
                        },
                        "settings":{}
                      }]
                    }
                    """.trimIndent()
                )
            )
        )

        val result = RotorApi(transport)
            .feedbackSource("user:onyourwave")

        assertEquals(YamResult.Success("user-123"), result)
        assertEquals("/rotor/stations/list", transport.lastRequest?.path)
        assertEquals(
            mapOf("language" to "ru"),
            transport.lastRequest?.query
        )
    }

    @Test
    fun feedbackUsesJsonAndBatchQuery() = runBlocking {
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
            YamHttpBody.Json(
                """{"type":"trackFinished","timestamp":123.5,"trackId":"10","totalPlayedSeconds":42.0}"""
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

        val body = transport.lastRequest?.body as YamHttpBody.Json
        assertTrue(body.value.contains("\"timestamp\":1785425833.541"))
        assertTrue(!body.value.contains("E9"))
    }

    @Test
    fun radioStartedIncludesProvidedFromField() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(YamHttpResponse(200, """{"result":"ok"}"""))
        )

        val result = RotorApi(
            transport = transport,
            timestampSeconds = { 1_785_425_833.0 }
        ).feedback(
            station = "user:onyourwave",
            type = RotorFeedbackType.RADIO_STARTED,
            from = "user-123",
            batchId = "batch-1"
        )

        assertEquals(YamResult.Success(Unit), result)
        val body = transport.lastRequest?.body as YamHttpBody.Json
        assertTrue(body.value.contains("\"from\":\"user-123\""))
        assertTrue(!body.value.contains("\"trackId\""))
    }

    @Test
    fun feedbackRetriesWithoutBatchWhenBatchConditionIsRejected() =
        runBlocking {
            val transport = FakeTransport(
                YamResult.Failure(
                    YamError.Http(
                        statusCode = 400,
                        code = "condition is not met"
                    )
                ),
                YamResult.Success(
                    YamHttpResponse(200, """{"result":"ok"}""")
                )
            )

            val result = RotorApi(
                transport = transport,
                timestampSeconds = { 123.5 }
            ).feedback(
                station = "user:onyourwave",
                type = RotorFeedbackType.TRACK_STARTED,
                trackId = "10",
                batchId = "batch-1"
            )

            assertEquals(YamResult.Success(Unit), result)
            assertEquals(2, transport.requests.size)
            assertEquals(
                mapOf("batch-id" to "batch-1"),
                transport.requests.first().query
            )
            assertTrue(transport.requests.last().query.isEmpty())
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
        vararg results: YamResult<YamHttpResponse>
    ) : YamTransport {
        private val results = results.toList()
        private var resultIndex = 0
        val requests = mutableListOf<YamHttpRequest>()
        val lastRequest: YamHttpRequest?
            get() = requests.lastOrNull()

        override suspend fun execute(
            request: YamHttpRequest
        ): YamResult<YamHttpResponse> {
            requests += request
            val result = results.getOrElse(resultIndex) {
                results.last()
            }
            resultIndex += 1
            return result
        }
    }
}
