package com.yellastrodev.yandexmusiclib.account

import com.yellastrodev.yandexmusiclib.YamApiClient
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

class AccountApiTest {

    @Test
    fun statusDecodesTypedAccount() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(
                YamHttpResponse(
                    statusCode = 200,
                    body = """
                        {
                          "invocationInfo":{"req-id":"request"},
                          "result":{
                            "account":{
                              "now":"2019-11-07T21:49:54+00:00",
                              "uid":1130000002804451,
                              "login":"Ilya@marshal.by",
                              "display_name":"Marshal",
                              "service_available":true,
                              "child":false
                            },
                            "cache_limit":99,
                            "station_exists":true
                          }
                        }
                    """.trimIndent()
                )
            )
        )

        val result = AccountApi(transport).status()

        assertTrue(result is YamResult.Success)
        val status = (result as YamResult.Success).value
        assertEquals(1130000002804451L, status.account?.uid)
        assertEquals("Ilya@marshal.by", status.account?.login)
        assertEquals("Marshal", status.account?.displayName)
        assertEquals(99, status.cacheLimit)
        assertEquals(true, status.stationExists)
        assertEquals(YamHttpMethod.GET, transport.lastRequest?.method)
        assertEquals("/account/status", transport.lastRequest?.path)
        assertEquals(true, transport.lastRequest?.requiresAuthorization)
    }

    @Test
    fun statusKeepsPythonNullableSemantics() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(
                YamHttpResponse(
                    200,
                    """{"result":{"account":{"service_available":true}}}"""
                )
            )
        )

        val result = AccountApi(transport).status()

        assertTrue(result is YamResult.Success)
        val account = (result as YamResult.Success).value.account
        assertNull(account?.uid)
        assertNull(account?.login)
    }

    @Test
    fun malformedJsonReturnsInvalidResponse() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(YamHttpResponse(200, "{broken"))
        )

        val result = AccountApi(transport).status()

        assertTrue(result is YamResult.Failure)
        assertTrue((result as YamResult.Failure).error is YamError.InvalidResponse)
    }

    @Test
    fun missingResultReturnsInvalidResponse() = runBlocking {
        val transport = FakeTransport(
            YamResult.Success(YamHttpResponse(200, """{"invocationInfo":{}}"""))
        )

        val result = AccountApi(transport).status()

        assertTrue(result is YamResult.Failure)
        assertTrue((result as YamResult.Failure).error is YamError.InvalidResponse)
    }

    @Test
    fun transportErrorIsPreserved() = runBlocking {
        val expected = YamResult.Failure(YamError.Timeout)
        val result = AccountApi(FakeTransport(expected)).status()

        assertEquals(expected, result)
    }

    @Test
    fun publicClientRejectsBlankTokenWithoutRequest() = runBlocking {
        val result = YamApiClient("", "").accountStatus()

        assertEquals(
            YamResult.Failure(YamError.Unauthorized),
            result
        )
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
