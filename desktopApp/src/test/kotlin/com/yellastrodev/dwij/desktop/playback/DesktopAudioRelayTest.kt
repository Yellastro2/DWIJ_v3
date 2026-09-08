package com.yellastrodev.dwij.desktop.playback

import com.yellastrodev.yamusicsdk.NoOpYamLogger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DesktopAudioRelayTest {
    private val audio = ByteArray(100_000) { (it % 251).toByte() }

    @Test fun `полный ответ HEAD и перемотка возвращают правильные заголовки и байты`() = runBlocking {
        relay().use { relay ->
            val url = relay.resolve("ya://123")
            assertEquals("127.0.0.1", URI(url).host)
            request(url).useResponse { connection ->
                assertEquals(200, connection.responseCode)
                assertEquals("audio/mpeg", connection.contentType)
                assertEquals("bytes", connection.getHeaderField("Accept-Ranges"))
                assertArrayEquals(audio, connection.inputStream.use { it.readBytes() })
            }
            request(url, method = "HEAD").useResponse { connection ->
                assertEquals(200, connection.responseCode)
                assertEquals(audio.size.toLong(), connection.contentLengthLong)
                assertEquals(0, connection.inputStream.use { it.readBytes() }.size)
            }
            request(url, "bytes=70000-79999").useResponse { connection ->
                assertEquals(206, connection.responseCode)
                assertEquals("bytes 70000-79999/100000", connection.getHeaderField("Content-Range"))
                assertEquals(10000L, connection.contentLengthLong)
                assertArrayEquals(audio.copyOfRange(70_000, 80_000), connection.inputStream.use { it.readBytes() })
            }
            request(url, "bytes=99990-").useResponse { connection ->
                assertEquals(206, connection.responseCode)
                assertArrayEquals(audio.takeLast(10).toByteArray(), connection.inputStream.use { it.readBytes() })
            }
        }
    }

    @Test fun `неверный Range и старые адреса не открывают произвольные треки`() = runBlocking {
        relay().use { relay ->
            val old = relay.resolve("ya://123")
            listOf("bytes=100000-", "bytes=0-1,5-8", "bytes=-0", "bytes=9-1").forEach { range ->
                request(old, range).useResponse {
                    assertEquals(416, it.responseCode)
                    assertEquals("bytes */100000", it.getHeaderField("Content-Range"))
                }
            }
            relay.resolve("ya://456")
            request(old).useResponse { assertEquals(404, it.responseCode) }
            request(old.substringBefore("/audio/") + "/audio/other.mp3").useResponse {
                assertEquals(404, it.responseCode)
            }
        }
    }

    @Test fun `ОС выделяет разные порты а закрытие освобождает источник`() = runBlocking {
        val closed = AtomicInteger()
        val first = relay(onClose = { closed.incrementAndGet() })
        val second = relay()
        try {
            val one = first.resolve("ya://1")
            val two = second.resolve("ya://1")
            assertTrue(URI(one).port > 0)
            assertNotEquals(URI(one).port, URI(two).port)
            first.close()
            first.close()
            assertEquals(1, closed.get())
            assertThrows(IOException::class.java) {
                request(one).useResponse { it.inputStream.read() }
            }
            Unit
        } finally {
            first.close()
            second.close()
        }
    }

    @Test fun `ошибка upstream до заголовков дает 502 и освобождает reader`() = runBlocking {
        val released = CountDownLatch(1)
        relay(open = {
            object : RelayAudioReader {
                override fun length(): Long = throw IOException("test failure")
                override fun read(position: Long, buffer: ByteArray, length: Int) = -1
                override fun close() { released.countDown() }
            }
        }).use { relay ->
            request(relay.resolve("ya://123")).useResponse { assertEquals(502, it.responseCode) }
            assertTrue(released.await(3, TimeUnit.SECONDS))
        }
    }

    @Test fun `смена трека прерывает ожидающий запрос и освобождает reader`() = runBlocking {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        val client = Executors.newSingleThreadExecutor()
        val relay = relay(open = {
            object : RelayAudioReader {
                override fun length(): Long {
                    started.countDown()
                    try { blocked.await() } catch (_: InterruptedException) { throw IOException("cancelled") }
                    return 100
                }
                override fun read(position: Long, buffer: ByteArray, length: Int) = -1
                override fun close() { released.countDown() }
            }
        })
        try {
            val url = relay.resolve("ya://1")
            val response = client.submit { runCatching { request(url).useResponse { it.responseCode } } }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            relay.resolve("ya://2")
            assertTrue(released.await(3, TimeUnit.SECONDS))
            response.get(3, TimeUnit.SECONDS)
            Unit
        } finally {
            blocked.countDown()
            relay.close()
            client.shutdownNow()
        }
    }

    @Test fun `suffix ограничение конца и If-Range fallback`() = runBlocking {
        assertEquals(90L..99L, parseRelayRange("bytes=-10", 100))
        assertEquals(0L..99L, parseRelayRange("bytes=-999", 100))
        assertEquals(95L..99L, parseRelayRange("bytes=95-200", 100))
        relay().use { relay ->
            request(relay.resolve("ya://1"), "bytes=50-").apply {
                setRequestProperty("If-Range", "unknown-version")
            }.useResponse {
                assertEquals(200, it.responseCode)
                assertArrayEquals(audio, it.inputStream.use { input -> input.readBytes() })
            }
        }
    }

    private fun relay(
        onClose: () -> Unit = {},
        open: (String) -> RelayAudioReader = {
            object : RelayAudioReader {
                override fun length() = audio.size.toLong()
                override fun read(position: Long, buffer: ByteArray, length: Int): Int {
                    val count = minOf(length, audio.size - position.toInt())
                    if (count <= 0) return -1
                    audio.copyInto(buffer, 0, position.toInt(), position.toInt() + count)
                    return count
                }
                override fun close() = Unit
            }
        },
    ) = DesktopAudioRelay(NoOpYamLogger, { null }, open, {}, onClose)

    private fun request(url: String, range: String? = null, method: String = "GET") =
        (URL(url).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection).apply {
            connectTimeout = 3_000
            readTimeout = 3_000
            requestMethod = method
            range?.let { setRequestProperty("Range", it) }
        }

    private inline fun <T> HttpURLConnection.useResponse(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }
}
