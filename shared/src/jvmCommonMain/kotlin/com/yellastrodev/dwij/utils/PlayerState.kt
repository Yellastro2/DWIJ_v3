package com.yellastrodev.dwij.utils

data class PlayerState(
    val isPlaying: Boolean = false,
    val currentIndex: Int = 0,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val isShuffle: Boolean = false,
    val isRepeatAll: Boolean = false
)

sealed class PlayerEvent {
    data class ShowError(val message: String) : PlayerEvent()
    data class TrackListEnd(val message: String) : PlayerEvent()
    // можно добавить другие события: SkipNext, SkipPrev и т.д.
}