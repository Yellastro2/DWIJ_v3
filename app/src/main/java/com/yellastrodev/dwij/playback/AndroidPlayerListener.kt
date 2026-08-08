package com.yellastrodev.dwij.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.yellastrodev.dwij.playback.feedback.PlaybackMetadataKeys
import com.yellastrodev.dwij.utils.PlayerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AndroidPlayerListener(
    private val player: ExoPlayer,
    private val scope: CoroutineScope,
    private val stateStore: PlaybackStateStore,
    private val feedback: AndroidPlaybackFeedbackAdapter,
    private val trackCoverLoader: TrackCoverLoader,
) : Player.Listener {

    private var progressJob: Job? = null

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        feedback.onMediaItemTransition(
            mediaItem = mediaItem,
            reason = reason,
            isPlaying = player.isPlaying,
            currentPositionMs = player.currentPosition,
            durationMs = player.duration,
        )

        if (
            mediaItem != null &&
            player.currentMediaItemIndex != C.INDEX_UNSET
        ) {
            stateStore.setCurrentIndex(player.currentMediaItemIndex)
        }

        if (mediaItem == null) return

        val isLocal = mediaItem.mediaMetadata.extras
            ?.getString(PlaybackMetadataKeys.MUSIC_SOURCE) ==
                PlaybackMetadataKeys.SOURCE_LOCAL

        if (
            isLocal ||
            mediaItem.mediaMetadata.artworkData != null ||
            mediaItem.mediaMetadata.artworkUri != null
        ) {
            return
        }

        loadArtwork(mediaItem)
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        stateStore.setPlayback(
            isPlaying = player.isPlaying,
            currentIndex = player.currentMediaItemIndex,
        )

        if (playbackState != Player.STATE_ENDED) return

        feedback.onPlaybackEnded(
            currentPositionMs = player.currentPosition,
            durationMs = player.duration,
            completed = true,
        )

        val playlistFinished =
            player.currentMediaItemIndex == player.mediaItemCount - 1 &&
                    player.repeatMode == Player.REPEAT_MODE_OFF

        if (playlistFinished) {
            scope.launch {
                stateStore.emit(PlayerEvent.TrackListEnd("Playlist finished"))
            }
        } else {
            // Не ходим обратно через PlayerRepository -> PlayerEngine -> PlayerService.
            player.seekToNext()
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        stateStore.setPlaying(isPlaying)

        feedback.onIsPlayingChanged(
            mediaItem = player.currentMediaItem,
            isPlaying = isPlaying,
            currentPositionMs = player.currentPosition,
            durationMs = player.duration,
        )

        if (isPlaying) startProgressUpdates() else stopProgressUpdates()
    }

    override fun onPlayerError(error: PlaybackException) {
        feedback.onPlaybackEnded(
            currentPositionMs = player.currentPosition,
            durationMs = player.duration,
            completed = false,
        )

        Log.e(TAG, "[onPlayerError] code=${error.errorCode}", error)

        scope.launch {
            stateStore.emit(PlayerEvent.ShowError("Ошибка воспроизведения"))
        }

        player.seekToNext()
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        stateStore.setShuffle(shuffleModeEnabled)
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        stateStore.setRepeatAll(repeatMode == Player.REPEAT_MODE_ALL)
    }

    fun release() {
        stopProgressUpdates()
    }

    private fun loadArtwork(mediaItem: MediaItem) {
        val trackId = mediaItem.mediaId

        scope.launch(Dispatchers.IO) {
            val coverBytes =
                trackCoverLoader.loadPlayerCover(trackId) ?: return@launch

            val newItem = mediaItem.buildUpon()
                .setMediaMetadata(
                    mediaItem.mediaMetadata.buildUpon()
                        .setArtworkData(
                            coverBytes,
                            MediaMetadata.PICTURE_TYPE_FRONT_COVER,
                        )
                        .build(),
                )
                .build()

            withContext(Dispatchers.Main.immediate) {
                val index = player.currentMediaItemIndex
                if (index == C.INDEX_UNSET) return@withContext
                if (player.getMediaItemAt(index).mediaId != trackId) return@withContext

                player.replaceMediaItem(index, newItem)
            }
        }
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                val positionMs = player.currentPosition
                val durationMs = player.duration

                stateStore.setProgress(positionMs, durationMs)

                feedback.onProgress(
                    trackId = player.currentMediaItem?.mediaId,
                    currentPositionMs = positionMs,
                    durationMs = durationMs,
                )

                delay(PROGRESS_INTERVAL_MS)
            }
        }
    }

    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
    }

    private companion object {
        const val TAG = "AndroidPlayerListener"
        const val PROGRESS_INTERVAL_MS = 500L
    }
}
