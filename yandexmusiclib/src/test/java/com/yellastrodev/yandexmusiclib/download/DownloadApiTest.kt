package com.yellastrodev.yandexmusiclib.download

import com.yellastrodev.yandexmusiclib.network.YamContentTransport
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

class DownloadApiTest {

    @Test
    fun directUrlUsesPythonDownloadInfoAndSignature() = runBlocking {
        val transport = FakeTransport(
            apiResult = YamResult.Success(
                YamHttpResponse(
                    200,
                    """
                    {
                      "result":[{
                        "codec":"mp3",
                        "bitrateInKbps":192,
                        "gain":false,
                        "preview":false,
                        "downloadInfoUrl":"https://info.example/xml",
                        "direct":false
                      }]
                    }
                    """.trimIndent()
                )
            ),
            contentResult = YamResult.Success(
                """
                <download-info>
                  <host>music.example</host>
                  <path>/path/file</path>
                  <ts>123</ts>
                  <s>abc</s>
                </download-info>
                """.trimIndent().toByteArray()
            )
        )

        val result = DownloadApi(transport, transport)
            .directDownloadUrl("10")

        assertEquals(
            YamResult.Success(
                "https://music.example/get-mp3/" +
                    "635ca7fd7758262c2812c7fe6feaebf6/123/path/file"
            ),
            result
        )
        assertEquals(YamHttpMethod.GET, transport.lastRequest?.method)
        assertEquals("/tracks/10/download-info", transport.lastRequest?.path)
        assertEquals("https://info.example/xml", transport.lastContentUrl)
        assertEquals(false, transport.lastContentRequiresAuthorization)
    }

    @Test
    fun downloadBytesRetrievesSignedUrlThroughContentTransport() = runBlocking {
        val xml = """
            <download-info>
              <host>music.example</host>
              <path>/path/file</path>
              <ts>123</ts>
              <s>abc</s>
            </download-info>
        """.trimIndent().toByteArray()
        val transport = FakeTransport(
            apiResult = YamResult.Success(
                YamHttpResponse(
                    200,
                    """
                    {"result":[{
                      "codec":"mp3",
                      "bitrateInKbps":192,
                      "gain":false,
                      "preview":false,
                      "downloadInfoUrl":"https://info.example/xml",
                      "direct":false
                    }]}
                    """.trimIndent()
                )
            ),
            contentResult = YamResult.Success(xml)
        )

        val result = DownloadApi(transport, transport)
            .downloadBytes("10")

        assertEquals(YamResult.Success(xml), result)
        assertEquals(2, transport.contentCalls)
        assertEquals(
            "https://music.example/get-mp3/" +
                "635ca7fd7758262c2812c7fe6feaebf6/123/path/file",
            transport.lastContentUrl
        )
        assertEquals(false, transport.lastContentRequiresAuthorization)
    }

    @Test
    fun missingDownloadInfoIsTypedFailure() = runBlocking {
        val transport = FakeTransport(
            apiResult = YamResult.Success(
                YamHttpResponse(200, """{"result":[]}""")
            ),
            contentResult = YamResult.Success(byteArrayOf())
        )

        val result = DownloadApi(transport, transport)
            .directDownloadUrl("10")

        assertTrue(result is YamResult.Failure)
        assertTrue((result as YamResult.Failure).error is YamError.InvalidResponse)
        assertNull(transport.lastContentUrl)
    }

    @Test
    fun invalidXmlIsTypedFailure() = runBlocking {
        val transport = FakeTransport(
            apiResult = YamResult.Success(
                YamHttpResponse(
                    200,
                    """
                    {"result":[{
                      "codec":"mp3",
                      "bitrateInKbps":192,
                      "gain":false,
                      "preview":false,
                      "downloadInfoUrl":"https://info.example/xml",
                      "direct":false
                    }]}
                    """.trimIndent()
                )
            ),
            contentResult = YamResult.Success("<broken".toByteArray())
        )

        val result = DownloadApi(transport, transport)
            .directDownloadUrl("10")

        assertTrue(result is YamResult.Failure)
        assertTrue((result as YamResult.Failure).error is YamError.InvalidResponse)
    }

    @Test
    fun doctypeInDownloadInfoIsRejectedAsTypedFailure() = runBlocking {
        val transport = FakeTransport(
            apiResult = YamResult.Success(
                YamHttpResponse(
                    200,
                    """
                    {"result":[{
                      "codec":"mp3",
                      "bitrateInKbps":192,
                      "gain":false,
                      "preview":false,
                      "downloadInfoUrl":"https://info.example/xml",
                      "direct":false
                    }]}
                    """.trimIndent()
                )
            ),
            contentResult = YamResult.Success(
                """
                <!DOCTYPE download-info [<!ENTITY host "music.example">]>
                <download-info>
                  <host>&host;</host>
                  <path>/path/file</path>
                  <ts>123</ts>
                  <s>abc</s>
                </download-info>
                """.trimIndent().toByteArray()
            )
        )

        val result = DownloadApi(transport, transport)
            .directDownloadUrl("10")

        assertTrue(result is YamResult.Failure)
        assertTrue(
            (result as YamResult.Failure).error is YamError.InvalidResponse
        )
    }

    private class FakeTransport(
        private val apiResult: YamResult<YamHttpResponse>,
        private val contentResult: YamResult<ByteArray>
    ) : YamTransport, YamContentTransport {
        var lastRequest: YamHttpRequest? = null
        var lastContentUrl: String? = null
        var lastContentRequiresAuthorization: Boolean? = null
        var contentCalls: Int = 0

        override suspend fun execute(
            request: YamHttpRequest
        ): YamResult<YamHttpResponse> {
            lastRequest = request
            return apiResult
        }

        override suspend fun retrieve(
            url: String,
            requiresAuthorization: Boolean
        ): YamResult<ByteArray> {
            contentCalls += 1
            lastContentUrl = url
            lastContentRequiresAuthorization = requiresAuthorization
            return contentResult
        }
    }
}
