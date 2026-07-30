package com.yellastrodev.yandexmusiclib.covers

import com.yellastrodev.yandexmusiclib.entities.CoverSize
import com.yellastrodev.yandexmusiclib.network.YamContentTransport
import com.yellastrodev.yandexmusiclib.network.YamResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverApiTest {

    @Test
    fun coverBuildsHttpsUrlAndPreservesBytes() = runBlocking {
        val transport = FakeContentTransport()

        val result = CoverApi(transport).bytes(
            "avatars.example/image/%%",
            CoverSize.`400x400`
        )

        assertTrue(result is YamResult.Success)
        assertEquals(
            "https://avatars.example/image/400x400",
            transport.lastUrl
        )
        assertEquals(true, transport.lastRequiresAuthorization)
    }

    private class FakeContentTransport : YamContentTransport {
        var lastUrl: String? = null
        var lastRequiresAuthorization: Boolean? = null

        override suspend fun retrieve(
            url: String,
            requiresAuthorization: Boolean
        ): YamResult<ByteArray> {
            lastUrl = url
            lastRequiresAuthorization = requiresAuthorization
            return YamResult.Success(byteArrayOf(1, 2, 3))
        }
    }
}
