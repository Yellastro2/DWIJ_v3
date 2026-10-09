package com.yellastrodev.dwij.playback

import org.junit.Assert.*
import org.junit.Test

/** Проверяет общий срок затыка без Android looper и реальной сети. */
class AndroidPlaybackAttemptTest {
    /** Повторные BUFFERING не продлевают двадцатисекундный срок подготовки. */
    @Test fun `подготовка имеет абсолютный срок`() {
        val attempt = AndroidPlaybackAttempt(startedMs = 100)
        attempt.onBuffering(1_000, true)
        attempt.onBuffering(9_000, true)
        assertEquals(20_100L, attempt.deadlineMs())
        attempt.onBuffering(11_000, false)
        assertEquals(20_100L, attempt.deadlineMs())
    }

    /** Повторы сегмента и уведомления BUFFERING сохраняют один общий срок. */
    @Test fun `после готовности ожидание ограничено двадцатью секундами`() {
        val attempt = AndroidPlaybackAttempt(0)
        attempt.onReady()
        assertNull(attempt.deadlineMs())
        attempt.onBuffering(20_000, true)
        attempt.onBuffering(24_000, true)
        assertEquals(40_000L, attempt.deadlineMs())
        attempt.onReady()
        assertNull(attempt.deadlineMs())
    }

    /** Пауза не расходует срок зависания, после возобновления отсчитывается новый бюджет. */
    @Test fun `пауза не является зависанием`() {
        val attempt = AndroidPlaybackAttempt(0)
        attempt.onReady()
        attempt.onBuffering(1_000, true)
        attempt.onBuffering(2_000, false)
        assertNull(attempt.deadlineMs())
        attempt.onBuffering(100_000, true)
        assertEquals(120_000L, attempt.deadlineMs())
    }

    /** Дублирующий callback ошибки не расходует ещё одну попытку. */
    @Test fun `отказ одной попытки принимается однократно`() {
        val attempt = AndroidPlaybackAttempt(0)
        assertTrue(attempt.fail())
        assertFalse(attempt.fail())
        assertNull(attempt.deadlineMs())
    }

    /** После успешной догрузки новый затык получает собственные двадцать секунд. */
    @Test fun `успешная догрузка завершает предыдущий затык`() {
        val attempt = AndroidPlaybackAttempt(0)
        attempt.onReady()
        attempt.onBuffering(1_000, true)
        assertEquals(21_000L, attempt.deadlineMs())
        attempt.onReady()
        attempt.onBuffering(50_000, true)
        assertEquals(70_000L, attempt.deadlineMs())
    }
}
