package com.yellastrodev.dwij.playback.feedback

import com.yellastrodev.dwij.data.entities.MusicSource

object PlaybackMetadataKeys {
    const val MUSIC_SOURCE = "playback_music_source"
    const val SOURCE_YANDEX = "yandex"
    const val SOURCE_LOCAL = "local"

    const val PLAY_ITEM_ID = "play_audio_item_id"
    const val PLAY_ALBUM_ID = "play_audio_album_id"
    const val PLAY_PLAYLIST_ID = "play_audio_playlist_id"
    const val PLAY_DURATION_MS = "play_audio_duration_ms"
    const val PLAY_SOURCE = "play_audio_source"
}

data class PlaybackFeedbackMetadata(
    val trackId: String,
    val itemId: String,
    val albumId: String?,
    val playlistId: String?,
    val reportSource: String,
    val durationMs: Long?,
    val musicSource: MusicSource,
)

enum class PlaybackTransitionReason {
    AUTO,
    PLAYLIST_CHANGED,
    OTHER,
}

enum class PlaybackReportType(
    val logValue: String,
) {
    START("start"),
    FINISH("finish"),
    INTERRUPTED("interrupted"),
}
