package com.yellastrodev.dwij.playback

import com.yellastrodev.dwij.utils.PlayerEvent
import com.yellastrodev.dwij.utils.PlayerState
import com.yellastrodev.dwij.utils.TrackChangeDirection
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class PlaybackStateStore {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>()
    val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    fun setPlayback(isPlaying: Boolean, currentIndex: Int) {
        _state.value = _state.value.copy(
            isPlaying = isPlaying,
            currentIndex = currentIndex,
        )
    }

    fun setCurrentIndex(index: Int) {
        _state.value = _state.value.copy(currentIndex = index)
    }

    fun setPlaying(isPlaying: Boolean) {
        _state.value = _state.value.copy(isPlaying = isPlaying)
    }

    fun setWantsToPlay(wantsToPlay: Boolean) {
        _state.value = _state.value.copy(wantsToPlay = wantsToPlay)
    }

    fun beginTrackChange(
        direction: TrackChangeDirection,
        wantsToPlay: Boolean = _state.value.wantsToPlay,
    ) {
        _state.value = _state.value.copy(
            wantsToPlay = wantsToPlay,
            pendingTrackChange = direction,
        )
    }

    fun completeTrackChange() {
        _state.value = _state.value.copy(pendingTrackChange = null)
    }

    fun setProgress(positionMs: Long, durationMs: Long) {
        _state.value = _state.value.copy(
            currentPosition = positionMs,
            duration = durationMs,
        )
    }

    fun setShuffle(enabled: Boolean) {
        _state.value = _state.value.copy(isShuffle = enabled)
    }

    fun setRepeatAll(enabled: Boolean) {
        _state.value = _state.value.copy(isRepeatAll = enabled)
    }

    suspend fun emit(event: PlayerEvent) {
        _events.emit(event)
    }
}
