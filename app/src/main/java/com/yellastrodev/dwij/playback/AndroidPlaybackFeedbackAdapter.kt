package com.yellastrodev.dwij.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.yellastrodev.dwij.data.entities.MusicSource
import com.yellastrodev.dwij.playback.feedback.PlaybackFeedbackMetadata
import com.yellastrodev.dwij.playback.feedback.PlaybackFeedbackTracker
import com.yellastrodev.dwij.playback.feedback.PlaybackMetadataKeys
import com.yellastrodev.dwij.playback.feedback.PlaybackTransitionReason

/** Переводит Media3-события в общие модели PlaybackFeedbackTracker. */
@OptIn(UnstableApi::class)
class AndroidPlaybackFeedbackAdapter(
    private val tracker: PlaybackFeedbackTracker,
) {
    fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
        isPlaying: Boolean,
        currentPositionMs: Long,
        durationMs: Long,
    ) {
        tracker.onMediaItemTransition(
            metadata = mediaItem?.toFeedbackMetadata(),
            reason = reason.toFeedbackReason(),
            isPlaying = isPlaying,
            currentPositionMs = currentPositionMs.normalizedPosition(),
            durationMs = durationMs.normalizedDuration(),
        )
    }

    fun onIsPlayingChanged(
        mediaItem: MediaItem?,
        isPlaying: Boolean,
        currentPositionMs: Long,
        durationMs: Long,
    ) {
        tracker.onIsPlayingChanged(
            metadata = mediaItem?.toFeedbackMetadata(),
            isPlaying = isPlaying,
            currentPositionMs = currentPositionMs.normalizedPosition(),
            durationMs = durationMs.normalizedDuration(),
        )
    }

    fun onProgress(
        trackId: String?,
        currentPositionMs: Long,
        durationMs: Long,
    ) {
        tracker.onProgress(
            trackId = trackId,
            currentPositionMs = currentPositionMs.normalizedPosition(),
            durationMs = durationMs.normalizedDuration(),
        )
    }

    fun onPlaybackEnded(
        currentPositionMs: Long,
        durationMs: Long,
        completed: Boolean,
    ) {
        tracker.onPlaybackEnded(
            currentPositionMs = currentPositionMs.normalizedPosition(),
            durationMs = durationMs.normalizedDuration(),
            completed = completed,
        )
    }

    private fun MediaItem.toFeedbackMetadata(): PlaybackFeedbackMetadata {
        val extras = mediaMetadata.extras

        val musicSource = when (
            extras?.getString(PlaybackMetadataKeys.MUSIC_SOURCE)
        ) {
            PlaybackMetadataKeys.SOURCE_LOCAL -> MusicSource.LOCAL
            else -> MusicSource.YANDEX
        }

        val metadataDurationMs = if (
            extras?.containsKey(PlaybackMetadataKeys.PLAY_DURATION_MS) == true
        ) {
            extras.getLong(PlaybackMetadataKeys.PLAY_DURATION_MS)
        } else {
            null
        }

        return PlaybackFeedbackMetadata(
            trackId = mediaId,
            itemId = extras
                ?.getString(PlaybackMetadataKeys.PLAY_ITEM_ID)
                ?: mediaId,
            albumId = extras?.getString(PlaybackMetadataKeys.PLAY_ALBUM_ID),
            playlistId = extras?.getString(PlaybackMetadataKeys.PLAY_PLAYLIST_ID),
            reportSource = extras
                ?.getString(PlaybackMetadataKeys.PLAY_SOURCE)
                ?: ANDROID_PLAYBACK_REPORT_SOURCE,
            durationMs = metadataDurationMs,
            musicSource = musicSource,
        )
    }

    private fun Int.toFeedbackReason(): PlaybackTransitionReason {
        return when (this) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ->
                PlaybackTransitionReason.AUTO
            Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED ->
                PlaybackTransitionReason.PLAYLIST_CHANGED
            else -> PlaybackTransitionReason.OTHER
        }
    }

    private fun Long.normalizedPosition(): Long {
        return takeUnless { value -> value == C.TIME_UNSET }
            ?.coerceAtLeast(0L)
            ?: 0L
    }

    private fun Long.normalizedDuration(): Long? {
        return takeIf { value ->
            value > 0L && value != C.TIME_UNSET
        }
    }

    private companion object {
        const val ANDROID_PLAYBACK_REPORT_SOURCE = "dwij-android"
    }
}
