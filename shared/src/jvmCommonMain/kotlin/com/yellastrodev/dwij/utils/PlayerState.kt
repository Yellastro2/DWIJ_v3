package com.yellastrodev.dwij.utils

/**
 * Общий снимок плеера: фактическое воспроизведение отделено от намерения
 * продолжить его после подготовки нового трека.
 */
data class PlayerState(
    val isPlaying: Boolean = false,
    val wantsToPlay: Boolean = false,
    val pendingTrackChange: TrackChangeDirection? = null,
    val currentIndex: Int = 0,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val isShuffle: Boolean = false,
    val isRepeatAll: Boolean = false
)

/** Направление ещё не завершённого перехода между треками. */
enum class TrackChangeDirection {
    PREVIOUS,
    NEXT,
    DIRECT,
}

sealed class PlayerEvent {
    data class ShowError(val message: String) : PlayerEvent()
    data class TrackListEnd(val message: String) : PlayerEvent()
    // можно добавить другие события: SkipNext, SkipPrev и т.д.
}
