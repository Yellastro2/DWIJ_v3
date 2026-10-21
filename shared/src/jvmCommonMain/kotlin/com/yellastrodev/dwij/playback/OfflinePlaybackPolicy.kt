package com.yellastrodev.dwij.playback

import com.yellastrodev.dwij.utils.PlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Считает окончательные сетевые отказы; три секунды реального прогресса обнуляют серию. */
internal class OfflinePlaybackPolicy {
    private val mutableOffline = MutableStateFlow(false)
    val offline = mutableOffline.asStateFlow()
    private val failures = mutableSetOf<Pair<Long, Int>>()
    private var previous: PlayerState? = null
    private var playedMs = 0L

    /** Новая пользовательская очередь начинает новую серию, но не снимает офлайн. */
    @Synchronized fun resetStreak() {
        failures.clear()
        previous = null
        playedMs = 0
    }

    /** Возвращает true после третьего разных отказавших элементов текущей серии. */
    @Synchronized fun failed(revision: Long, index: Int, networkFailure: Boolean): Boolean {
        if (mutableOffline.value) return true
        if (!networkFailure) return false
        failures += revision to index
        if (failures.size >= 3) mutableOffline.value = true
        previous = null
        playedMs = 0
        return mutableOffline.value
    }

    /** Seek и стоящий READY не считаются прослушиванием; пауза не добавляет время. */
    @Synchronized fun progress(state: PlayerState) {
        val old = previous
        if (old?.currentIndex != state.currentIndex) playedMs = 0
        if (old != null && old.currentIndex == state.currentIndex && old.isPlaying && state.isPlaying) {
            val delta = state.currentPosition - old.currentPosition
            if (delta in 1..1_500) playedMs += delta else if (delta != 0L) playedMs = 0
            if (playedMs >= 3_000) failures.clear()
        }
        previous = state
    }

    /** Только успешный сетевой probe возвращает обычное воспроизведение. */
    @Synchronized fun onlineConfirmed() {
        mutableOffline.value = false
        resetStreak()
    }
}
