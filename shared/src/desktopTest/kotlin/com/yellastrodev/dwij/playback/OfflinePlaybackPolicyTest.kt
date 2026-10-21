package com.yellastrodev.dwij.playback

import com.yellastrodev.dwij.utils.PlayerState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Проверяет порог окончательных отказов независимо от платформенных ретраев и состояния экрана. */
class OfflinePlaybackPolicyTest {
    /** Повторные сообщения одной позиции и несетевые отказы не заменяют три отказавших элемента. */
    @Test fun countsOnlyThreeTerminalNetworkFailures() {
        val policy = OfflinePlaybackPolicy()
        assertFalse(policy.failed(1, 0, true))
        assertFalse(policy.failed(1, 0, true))
        assertFalse(policy.failed(1, 1, false))
        assertFalse(policy.failed(1, 1, true))
        assertTrue(policy.failed(1, 2, true))
        assertTrue(policy.offline.value)
    }

    /** READY, пауза и прыжок позиции не обнуляют серию; три секунды реального продвижения обнуляют. */
    @Test fun resetsOnlyAfterActualPlayback() {
        val policy = OfflinePlaybackPolicy()
        policy.failed(1, 0, true)
        policy.failed(1, 1, true)
        policy.progress(PlayerState(currentIndex = 2, currentPosition = 0))
        policy.progress(PlayerState(currentIndex = 2, currentPosition = 50_000))
        policy.progress(PlayerState(currentIndex = 2, currentPosition = 50_000, isPlaying = true))
        for (position in 51_000L..53_000L step 1_000) {
            policy.progress(PlayerState(currentIndex = 2, currentPosition = position, isPlaying = true))
        }
        assertFalse(policy.failed(1, 3, true))
        assertFalse(policy.failed(1, 4, true))
        assertTrue(policy.failed(1, 5, true))
    }

    /** Крупный seek сам по себе не означает успешный запуск. */
    @Test fun seekDoesNotResetStreak() {
        val policy = OfflinePlaybackPolicy()
        policy.failed(1, 0, true)
        policy.failed(1, 1, true)
        policy.progress(PlayerState(currentIndex = 2, isPlaying = true))
        policy.progress(PlayerState(currentIndex = 2, isPlaying = true, currentPosition = 60_000))
        assertTrue(policy.failed(1, 2, true))
    }

    /** Новая очередь очищает отказы; локальная музыка и новая очередь не выключают уже включённый офлайн. */
    @Test fun offlineRequiresNetworkConfirmationToExit() {
        val policy = OfflinePlaybackPolicy()
        policy.failed(1, 0, true)
        policy.failed(1, 1, true)
        policy.resetStreak()
        assertFalse(policy.failed(2, 0, true))
        assertFalse(policy.failed(2, 1, true))
        assertTrue(policy.failed(2, 2, true))
        policy.resetStreak()
        for (position in 0L..4_000L step 1_000) {
            policy.progress(PlayerState(isPlaying = true, currentPosition = position))
        }
        assertTrue(policy.offline.value)
        policy.onlineConfirmed()
        assertFalse(policy.offline.value)
        assertFalse(policy.failed(3, 0, true))
    }
}
