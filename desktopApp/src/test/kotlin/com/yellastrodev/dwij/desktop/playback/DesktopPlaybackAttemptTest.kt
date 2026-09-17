package com.yellastrodev.dwij.desktop.playback

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Проверяет ограничение повторов и объединение одновременных отказов плеера и таймера. */
class DesktopPlaybackAttemptTest {
    /** Первая попытка и ровно три повтора; четвёртый отказ требует пропуска трека. */
    @Test fun `три повтора после первой попытки`() {
        var attempt = DesktopPlaybackAttempt(index = 7)
        var runs = 1
        while (attempt.canRetry) {
            assertTrue(attempt.fail())
            attempt = DesktopPlaybackAttempt(attempt.index, attempt.retry + 1)
            runs++
        }
        assertEquals(4, runs)
        assertTrue(attempt.fail())
        assertFalse(attempt.canRetry)
        assertTrue(DesktopPlaybackAttempt(index = 8).canRetry)
    }

    /** Несколько конкурентных callback не расходуют несколько повторов одной попытки. */
    @Test fun `одновременные ошибки принимаются один раз`() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val attempt = DesktopPlaybackAttempt(index = 0)
            val results = (1..20).map { executor.submit<Boolean> { attempt.fail() } }
            assertEquals(1, results.count { it.get(2, TimeUnit.SECONDS) })
            assertTrue(attempt.isFailed)
        } finally {
            executor.shutdownNow()
        }
    }
}
