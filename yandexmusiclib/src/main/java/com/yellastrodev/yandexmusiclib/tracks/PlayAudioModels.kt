package com.yellastrodev.yandexmusiclib.tracks

/**
 * Состояние одного воспроизведения для универсального `/play-audio`.
 *
 * Один и тот же [playId] следует использовать для стартового и финального
 * отчётов конкретного запуска трека.
 */
data class PlayAudioRequest(
    val trackId: String,
    val source: String,
    val albumId: String,
    val playId: String,
    val playlistId: String? = null,
    val fromCache: Boolean = false,
    val uid: Long? = null,
    val timestamp: String? = null,
    val trackLengthSeconds: Int = 0,
    val totalPlayedSeconds: Double = 0.0,
    val endPositionSeconds: Double = 0.0,
    val clientNow: String? = null
)
