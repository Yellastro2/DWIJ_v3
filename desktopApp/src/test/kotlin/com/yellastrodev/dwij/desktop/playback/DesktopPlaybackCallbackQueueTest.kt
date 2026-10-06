package com.yellastrodev.dwij.desktop.playback

import com.yellastrodev.yamusicsdk.NoOpYamLogger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Проверяет изоляцию вызывающего потока, объединение прогресса и жизненный цикл очереди. */
class DesktopPlaybackCallbackQueueTest {
    /** Медленное фоновое действие не задерживает постановку событий; прогресс не накапливается. */
    @Test fun `медленный обработчик сохраняет только последний прогресс`() = runBlocking {
        val queue = DesktopPlaybackCallbackQueue(NoOpYamLogger)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val values = mutableListOf<Int>()
        val callerThread = Thread.currentThread()
        try {
            queue.post {
                assertNotSame(callerThread, Thread.currentThread())
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            repeat(10_000) { value -> queue.postProgress { values.add(value) } }
            queue.post { values.add(-1) }
            release.countDown()
            val result = withTimeout(2_000) { queue.call { values.toList() } }
            assertEquals(listOf(9_999, -1), result)
        } finally {
            release.countDown()
            queue.close()
        }
    }

    /** Ошибка фонового действия не останавливает очередь; ошибка call возвращается его вызывающему коду. */
    @Test fun `ошибки заданий не ломают следующие события`() = runBlocking {
        val queue = DesktopPlaybackCallbackQueue(NoOpYamLogger)
        try {
            queue.post { error("Ошибка события") }
            assertEquals(7, withTimeout(2_000) { queue.call { 7 } })
            val failure = runCatching {
                withTimeout(2_000) { queue.call<Unit> { error("Ошибка барьера") } }
            }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("Ошибка барьера", failure?.message)
            assertEquals(8, withTimeout(2_000) { queue.call { 8 } })
        } finally {
            queue.close()
        }
    }

    /** Закрытие отклоняет новые события и даёт завершиться уже принятому действию. */
    @Test fun `закрытая очередь завершает принятые задания`() {
        val queue = DesktopPlaybackCallbackQueue(NoOpYamLogger)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        try {
            queue.post {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                finished.countDown()
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            queue.close()
            assertFalse(queue.post { fail("Новое событие после закрытия") })
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            queue.close()
        }
    }
}
