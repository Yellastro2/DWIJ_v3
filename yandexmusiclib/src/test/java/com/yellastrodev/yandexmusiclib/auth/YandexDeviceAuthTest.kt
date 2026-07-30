package com.yellastrodev.yandexmusiclib.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class YandexDeviceAuthTest {

    @Test
    fun requestDeviceCodeReturnsTypedModelAndSendsPythonPayload() = runBlocking {
        val transport = RecordingTransport(deviceCodeResponse())
        val auth = createAuth(transport)

        val result = auth.requestDeviceCode(deviceId = "my-id")

        assertTrue(result is DeviceAuthResult.Success)
        val code = (result as DeviceAuthResult.Success).value
        assertEquals("dev123", code.deviceCode)
        assertEquals("USER01", code.userCode)
        assertEquals(
            mapOf(
                "client_id" to "client-id",
                "device_id" to "my-id",
                "device_name" to "YandexMusicAPI"
            ),
            transport.requests.single().fields
        )
    }

    @Test
    fun pendingIsSuccessWithNullToken() = runBlocking {
        val transport = RecordingTransport(
            OAuthHttpResponse(
                statusCode = 400,
                body = """{"error":"authorization_pending","error_description":"wait"}"""
            )
        )
        val auth = createAuth(transport)

        val result = auth.pollDeviceToken("dev123")

        assertTrue(result is DeviceAuthResult.Success)
        assertNull((result as DeviceAuthResult.Success).value)
    }

    @Test
    fun authorizePollsUntilTokenArrives() = runBlocking {
        val transport = RecordingTransport(
            deviceCodeResponse(),
            OAuthHttpResponse(
                400,
                """{"error":"authorization_pending"}"""
            ),
            OAuthHttpResponse(
                400,
                """{"error":"authorization_pending"}"""
            ),
            OAuthHttpResponse(
                200,
                """
                    {
                      "access_token":"y0_token",
                      "refresh_token":"refresh",
                      "expires_in":31536000,
                      "token_type":"bearer"
                    }
                """.trimIndent()
            )
        )
        var delayCount = 0
        val auth = createAuth(
            transport = transport,
            delayMillis = { delayCount++ }
        )
        var shownCode: DeviceCode? = null

        val result = auth.authorize(onCode = { shownCode = it })

        assertTrue(result is DeviceAuthResult.Success)
        val token = (result as DeviceAuthResult.Success).value
        assertEquals("y0_token", token.accessToken)
        assertEquals("refresh", token.refreshToken)
        assertEquals("USER01", shownCode?.userCode)
        assertEquals(2, delayCount)
        assertEquals(
            mapOf(
                "grant_type" to "device_code",
                "code" to "dev123",
                "client_id" to "client-id",
                "client_secret" to "client-secret"
            ),
            transport.requests.last().fields
        )
    }

    @Test
    fun authorizeReturnsTypedTimeout() = runBlocking {
        val times = ArrayDeque(listOf(0L, 2_000L))
        val transport = RecordingTransport(
            deviceCodeResponse(expiresIn = 1),
            OAuthHttpResponse(
                400,
                """{"error":"authorization_pending"}"""
            )
        )
        val auth = createAuth(
            transport = transport,
            nowMillis = { times.removeFirst() }
        )

        val result = auth.authorize(onCode = {})

        assertEquals(
            DeviceAuthResult.Failure(DeviceAuthError.Timeout(1)),
            result
        )
    }

    @Test
    fun callerCancellationReturnsTypedError() = runBlocking {
        val transport = RecordingTransport(deviceCodeResponse())
        val auth = createAuth(transport)

        val result = auth.authorize(
            onCode = {},
            shouldCancel = { true }
        )

        assertEquals(
            DeviceAuthResult.Failure(DeviceAuthError.Cancelled),
            result
        )
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun coroutineCancellationIsPropagated() = runBlocking {
        val pollStarted = CompletableDeferred<Unit>()
        var requestNumber = 0
        val transport = DeviceAuthTransport { _, _ ->
            if (requestNumber++ == 0) {
                deviceCodeResponse()
            } else {
                pollStarted.complete(Unit)
                suspendCancellableCoroutine { }
            }
        }
        val auth = createAuth(transport)

        val job = launch {
            auth.authorize(onCode = {})
        }
        pollStarted.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
    }

    @Test
    fun oauthErrorIsTyped() = runBlocking {
        val transport = RecordingTransport(
            OAuthHttpResponse(
                400,
                """{"error":"expired_token","error_description":"expired"}"""
            )
        )
        val auth = createAuth(transport)

        val result = auth.pollDeviceToken("dev123")

        assertEquals(
            DeviceAuthResult.Failure(
                DeviceAuthError.OAuth("expired_token", "expired")
            ),
            result
        )
    }

    @Test
    fun networkErrorIsTyped() = runBlocking {
        val transport = DeviceAuthTransport { _, _ ->
            throw IOException("offline")
        }
        val auth = createAuth(transport)

        val result = auth.requestDeviceCode()

        assertTrue(result is DeviceAuthResult.Failure)
        assertTrue(
            (result as DeviceAuthResult.Failure).error is DeviceAuthError.Network
        )
    }

    @Test
    fun malformedJsonIsInvalidResponse() = runBlocking {
        val auth = createAuth(
            RecordingTransport(OAuthHttpResponse(200, "{broken"))
        )

        val result = auth.requestDeviceCode()

        assertTrue(result is DeviceAuthResult.Failure)
        assertTrue(
            (result as DeviceAuthResult.Failure).error is DeviceAuthError.InvalidResponse
        )
    }

    private fun createAuth(
        transport: DeviceAuthTransport,
        nowMillis: () -> Long = { 0L },
        delayMillis: suspend (Long) -> Unit = {}
    ): YandexDeviceAuth = YandexDeviceAuth(
        clientId = "client-id",
        clientSecret = "client-secret",
        deviceName = "YandexMusicAPI",
        logger = SilentLogger,
        transport = transport,
        nowMillis = nowMillis,
        delayMillis = delayMillis,
        deviceIdFactory = { "random-id" }
    )

    private fun deviceCodeResponse(expiresIn: Long = 300): OAuthHttpResponse =
        OAuthHttpResponse(
            200,
            """
                {
                  "device_code":"dev123",
                  "user_code":"USER01",
                  "verification_url":"https://oauth.yandex.ru/authorize/device",
                  "expires_in":$expiresIn,
                  "interval":5
                }
            """.trimIndent()
        )

    private data class RecordedRequest(
        val url: String,
        val fields: Map<String, String>
    )

    private class RecordingTransport(
        vararg responses: OAuthHttpResponse
    ) : DeviceAuthTransport {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<RecordedRequest>()

        override suspend fun postForm(
            url: String,
            fields: Map<String, String>
        ): OAuthHttpResponse {
            requests += RecordedRequest(url, fields)
            return responses.removeFirst()
        }
    }

    private object SilentLogger : DeviceAuthLogger {
        override fun debug(message: String) = Unit
        override fun warning(message: String) = Unit
        override fun error(message: String, cause: Throwable?) = Unit
    }
}
