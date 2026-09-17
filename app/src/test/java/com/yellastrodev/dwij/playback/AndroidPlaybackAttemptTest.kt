package com.yellastrodev.dwij.playback

import org.junit.Assert.*
import org.junit.Test

/** Проверяет сроки и число повторов без Android looper и реальной сети. */
class AndroidPlaybackAttemptTest {
    /** Повторные BUFFERING не превращают двенадцать секунд в бесконечное ожидание. */
    @Test fun `подготовка имеет абсолютный срок`() {
        val attempt = AndroidPlaybackAttempt(startedMs = 100)
        attempt.onBuffering(1_000, true)
        attempt.onBuffering(9_000, true)
        assertEquals(12_100L, attempt.deadlineMs())
        attempt.onBuffering(11_000, false)
        assertEquals(12_100L, attempt.deadlineMs())
    }

    /** Зависание после READY ограничено пятью секундами; READY снимает срок. */
    @Test fun `после готовности ожидание ограничено пятью секундами`() {
        val attempt = AndroidPlaybackAttempt(0)
        attempt.onReady()
        assertNull(attempt.deadlineMs())
        attempt.onBuffering(20_000, true)
        attempt.onBuffering(24_000, true)
        assertEquals(25_000L, attempt.deadlineMs())
        attempt.onReady()
        assertNull(attempt.deadlineMs())
    }

    /** Пауза не расходует срок зависания, после возобновления отсчитываются новые пять секунд. */
    @Test fun `пауза не является зависанием`() {
        val attempt = AndroidPlaybackAttempt(0)
        attempt.onReady()
        attempt.onBuffering(1_000, true)
        attempt.onBuffering(2_000, false)
        assertNull(attempt.deadlineMs())
        attempt.onBuffering(100_000, true)
        assertEquals(105_000L, attempt.deadlineMs())
    }

    /** Дублирующий callback ошибки не расходует ещё одну попытку. */
    @Test fun `отказ одной попытки принимается однократно`() {
        val attempt = AndroidPlaybackAttempt(0)
        assertTrue(attempt.fail())
        assertFalse(attempt.fail())
        assertNull(attempt.deadlineMs())
    }

    /** READY не обнуляет бюджет; новый трек получает собственные три повтора. */
    @Test fun `всего четыре попытки на трек`() {
        for (retry in 0..3) {
            val attempt = AndroidPlaybackAttempt(0, retry)
            attempt.onReady()
            assertEquals(retry < 3, attempt.canRetry)
        }
        assertTrue(AndroidPlaybackAttempt(0).canRetry)
    }
}
