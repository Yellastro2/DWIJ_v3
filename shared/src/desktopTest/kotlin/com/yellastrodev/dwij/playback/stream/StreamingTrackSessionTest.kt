package com.yellastrodev.dwij.playback.stream

import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.yamusicsdk.download.AudioRange
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Collections
import kotlin.test.*

class StreamingTrackSessionTest {
    @Test fun `seek получает приоритет а полный файл не содержит дырок`() = runBlocking {
        val data = ByteArray(900_000) { (it % 251).toByte() }
        val first = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val requests = Collections.synchronizedList(mutableListOf<Long>())
        val source = FakeSource { start, length, headers, bytes ->
            requests += start
            val end = minOf(data.size.toLong(), start + length).toInt()
            headers(AudioRange(start, end - start, data.size.toLong(), "v1"))
            val chunk = data.copyOfRange(start.toInt(), end)
            bytes(start, chunk, chunk.size)
            if (requests.size == 1) {
                first.complete(Unit)
                resume.await()
            }
            DataResult.Success(Unit)
        }
        Fixture(source).use { fixture ->
            withTimeout(5_000) {
                first.await()
                // Первый буфер читается до окончания первого HTTP-ответа.
                val head = ByteArray(16)
                assertEquals(16, fixture.session.read(0, head, 0, head.size))
                assertContentEquals(data.copyOfRange(0, 16), head)
                assertFalse(fixture.target.exists())
                fixture.session.length(700_000)
                resume.complete(Unit)
                fixture.published.await()
                assertEquals(700_000L, requests[1])
                assertContentEquals(data, fixture.target.readBytes())
                assertFalse(fixture.part.exists())
            }
        }
    }

    @Test fun `ответ без Range читается с начала и публикуется целиком`() = runBlocking {
        val data = ByteArray(1_000) { (it % 251).toByte() }
        Fixture(FakeSource { _, _, headers, bytes ->
            headers(AudioRange(0, data.size.toLong(), data.size.toLong(), null))
            bytes(0, data, data.size)
            DataResult.Success(Unit)
        }).use { fixture ->
            withTimeout(5_000) { fixture.published.await() }
            val tail = ByteArray(50)
            assertEquals(50, fixture.session.read(950, tail, 0, 50))
            assertContentEquals(data.copyOfRange(950, 1_000), tail)
            assertEquals(-1, fixture.session.read(1_000, tail, 0, 50))
        }
    }

    @Test fun `отмена будит читателя и удаляет временный файл`() = runBlocking {
        val first = CompletableDeferred<Unit>()
        Fixture(FakeSource { _, _, headers, bytes ->
            headers(AudioRange(0, 100, 100, null))
            bytes(0, byteArrayOf(1), 1)
            first.complete(Unit)
            awaitCancellation()
        }).use { fixture ->
            withTimeout(5_000) { first.await() }
            fixture.session.close()
            withTimeout(5_000) { fixture.session.awaitClosed() }
            assertFalse(fixture.part.exists())
            assertFalse(fixture.target.exists())
            assertFailsWith<IOException> { fixture.session.read(50, ByteArray(1), 0, 1) }
        }
    }

    @Test fun `ошибка после последнего байта не публикует неподтвержденный ответ`() = runBlocking {
        Fixture(FakeSource { _, _, headers, bytes ->
            headers(AudioRange(0, 2, 2, null))
            bytes(0, byteArrayOf(1, 2), 2)
            DataResult.Failure(DataError.InvalidData("Оборванный HTTP-ответ"))
        }).use { fixture ->
            withTimeout(5_000) { fixture.failed.await() }
            assertFalse(fixture.target.exists())
            assertFalse(fixture.part.exists())
        }
    }

    @Test fun `повторное открытие сохраняет сессию смена трека удаляет ее`() = runBlocking {
        val first = CompletableDeferred<Unit>()
        val source = FakeSource { _, _, headers, _ ->
            headers(AudioRange(0, 100, 100, null))
            first.complete(Unit)
            awaitCancellation()
        }
        Fixture(source, start = false).use { fixture ->
            val cache = StreamingTrackCache({ _, _ -> fixture.session }, {})
            try {
                cache.selectTrack("1")
                val session = cache.acquire("1")
                withTimeout(5_000) { first.await() }
                cache.release("1", session)
                assertTrue(fixture.part.exists())
                assertSame(session, cache.acquire("1"))
                cache.selectTrack("2")
                withTimeout(5_000) { session.awaitClosed() }
                assertFalse(fixture.part.exists())
            } finally {
                cache.close()
            }
        }
    }

    @Test fun `сохранение делит загрузку с плеером и переживает смену трека`() = runBlocking {
        val first = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val copying = CompletableDeferred<Unit>()
        var resolved = 0
        val source = object : StreamingAudioSource {
            override suspend fun resolveUrl(trackId: String): DataResult<String> {
                resolved++
                return DataResult.Success("same-audio")
            }
            override suspend fun readRange(
                url: String, start: Long, length: Long,
                onHeaders: (AudioRange) -> Unit, onBytes: (Long, ByteArray, Int) -> Unit,
            ): DataResult<Unit> {
                onHeaders(AudioRange(0, 4, 4, null))
                onBytes(0, byteArrayOf(1, 2), 2)
                first.complete(Unit)
                resume.await()
                onBytes(2, byteArrayOf(3, 4), 2)
                return DataResult.Success(Unit)
            }
        }
        Fixture(source, start = false).use { fixture ->
            val cache = StreamingTrackCache({ _, _ -> fixture.session }, {})
            try {
                withTimeout(5_000) {
                    cache.selectTrack("1")
                    val session = cache.acquire("1")
                    first.await()
                    val output = ByteArrayOutputStream()
                    val saved = async {
                        cache.copyTo("1", output) { _, _ -> copying.complete(Unit) }
                    }
                    copying.await()
                    cache.selectTrack("2")
                    cache.release("1", session)
                    assertFalse(session.isFailed())
                    resume.complete(Unit)
                    assertEquals(4L, saved.await())
                    assertEquals(1, resolved)
                    assertContentEquals(byteArrayOf(1, 2, 3, 4), output.toByteArray())
                }
            } finally {
                cache.close()
            }
        }
    }

    private class FakeSource(
        val fetch: suspend (Long, Long, (AudioRange) -> Unit, (Long, ByteArray, Int) -> Unit) -> DataResult<Unit>,
    ) : StreamingAudioSource {
        override suspend fun resolveUrl(trackId: String) = DataResult.Success("test-audio")
        override suspend fun readRange(
            url: String, start: Long, length: Long,
            onHeaders: (AudioRange) -> Unit, onBytes: (Long, ByteArray, Int) -> Unit,
        ) = fetch(start, length, onHeaders, onBytes)
    }

    private class Fixture(source: StreamingAudioSource, start: Boolean = true) : AutoCloseable {
        val directory = Files.createTempDirectory("dwij-stream-test").toFile()
        val part = File(directory, "audio.part")
        val target = File(directory, "audio.mp3")
        val published = CompletableDeferred<Unit>()
        val failed = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = StreamingTrackSession("1", part, source, scope,
            publish = { Files.move(it.toPath(), target.toPath()); target },
            trimCache = { published.complete(Unit) },
            onFailure = { failed.complete(Unit) },
        ).also { if (start) it.start() }

        override fun close() {
            session.close()
            runBlocking { withTimeout(5_000) { session.awaitClosed() } }
            scope.cancel()
            directory.deleteRecursively()
        }
    }
}
