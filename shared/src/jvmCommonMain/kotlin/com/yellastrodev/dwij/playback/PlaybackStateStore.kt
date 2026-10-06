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
import kotlinx.coroutines.flow.update

/** Атомарно публикует состояние: фоновые обновления прогресса не затирают конкурентные команды. */
class PlaybackStateStore {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>()
    val events: SharedFlow<PlayerEvent> = _events.asSharedFlow()

    /** Обновляет фактическое воспроизведение и индекс, сохраняя конкурентные изменения остальных полей. */
    fun setPlayback(isPlaying: Boolean, currentIndex: Int) {
        _state.update { it.copy(isPlaying = isPlaying, currentIndex = currentIndex) }
    }

    /** Меняет только текущий индекс очереди. */
    fun setCurrentIndex(index: Int) {
        _state.update { it.copy(currentIndex = index) }
    }

    /** Меняет фактическое состояние воспроизведения без потери других полей. */
    fun setPlaying(isPlaying: Boolean) {
        _state.update { it.copy(isPlaying = isPlaying) }
    }

    /** Запоминает намерение пользователя независимо от частого прогресса. */
    fun setWantsToPlay(wantsToPlay: Boolean) {
        _state.update { it.copy(wantsToPlay = wantsToPlay) }
    }

    /** Устанавливает направление перехода и намерение воспроизведения одним атомарным обновлением. */
    fun beginTrackChange(
        direction: TrackChangeDirection,
        wantsToPlay: Boolean = _state.value.wantsToPlay,
    ) {
        _state.update { it.copy(wantsToPlay = wantsToPlay, pendingTrackChange = direction) }
    }

    /** Снимает индикатор перехода, сохраняя актуальные команды и прогресс. */
    fun completeTrackChange() {
        _state.update { it.copy(pendingTrackChange = null) }
    }

    /** Обновляет позицию и длительность, не перезаписывая конкурентную паузу или режимы очереди. */
    fun setProgress(positionMs: Long, durationMs: Long) {
        _state.update { it.copy(currentPosition = positionMs, duration = durationMs) }
    }

    /** Меняет только признак случайного порядка. */
    fun setShuffle(enabled: Boolean) {
        _state.update { it.copy(isShuffle = enabled) }
    }

    /** Меняет только признак повтора очереди. */
    fun setRepeatAll(enabled: Boolean) {
        _state.update { it.copy(isRepeatAll = enabled) }
    }

    suspend fun emit(event: PlayerEvent) {
        _events.emit(event)
    }
}
