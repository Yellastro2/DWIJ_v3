package com.yellastrodev.dwij.service

import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.yellastrodev.dwij.data.repo.TrackCacheRepository
import com.yellastrodev.dwij.data.source.YaLazyDataSourceFactory
import com.yellastrodev.dwij.playback.AndroidPlaybackFeedbackAdapter
import com.yellastrodev.dwij.playback.AndroidPlayerListener
import com.yellastrodev.dwij.playback.PlaybackStateStore
import com.yellastrodev.dwij.utils.PlayerEvent
import com.yellastrodev.dwij.utils.PlayerState
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class PlayerService : MediaSessionService() {

    lateinit var player: ExoPlayer
        private set

    private lateinit var mediaSession: MediaSession
    private lateinit var playerListener: AndroidPlayerListener

    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val applicationComponent by lazy {
        (application as yApplication).component
    }

    private val trackCacheRepo: TrackCacheRepository by lazy {
        applicationComponent.trackCacheRepo
    }

    private val playbackFeedback by lazy {
        AndroidPlaybackFeedbackAdapter(
            tracker = applicationComponent.playbackFeedbackTracker,
        )
    }

    private val stateStore = PlaybackStateStore()

    val state: StateFlow<PlayerState> = stateStore.state
    val events: SharedFlow<PlayerEvent> = stateStore.events

    override fun onCreate() {
        super.onCreate()

        val dataSourceFactory = YaLazyDataSourceFactory(this, trackCacheRepo)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()

        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )

        mediaSession = MediaSession.Builder(this, player).build()
        addSession(mediaSession)

        playerListener = AndroidPlayerListener(
            player = player,
            scope = serviceScope,
            stateStore = stateStore,
            feedback = playbackFeedback,
            trackCoverLoader = applicationComponent.trackCoverLoader,
        )
        player.addListener(playerListener)

        (application as yApplication)
            .playerServiceRegistry
            .attach(this)
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaSession = mediaSession

    fun playQueue(
        tracks: List<MediaItem>,
        startIndex: Int = 0,
    ) {
        player.setMediaItems(tracks, startIndex, 0L)
        player.prepare()
        player.play()
    }

    fun addTracks(items: List<MediaItem>) {
        player.addMediaItems(items)
    }

    fun playTrack(trackNumber: Int) {
        if (trackNumber !in 0 until player.mediaItemCount) {
            Log.w(TAG, "[playTrack] invalid index=$trackNumber")
            return
        }

        player.seekTo(trackNumber, 0L)
        player.playWhenReady = true

        if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }
    }

    fun pause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun skipNext() {
        player.seekToNext()
    }

    fun skipPrev() {
        player.seekToPrevious()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
    }

    override fun onDestroy() {
        if (::player.isInitialized) {
            playbackFeedback.onPlaybackEnded(
                currentPositionMs = player.currentPosition,
                durationMs = player.duration,
                completed = false,
            )
        }

        (application as yApplication)
            .playerServiceRegistry
            .detach(this)

        if (::playerListener.isInitialized) {
            player.removeListener(playerListener)
            playerListener.release()
        }

        serviceScope.cancel()

        if (::mediaSession.isInitialized) {
            mediaSession.release()
        }
        if (::player.isInitialized) {
            player.release()
        }

        super.onDestroy()
    }

    private companion object {
        const val TAG = "PlayerService"
    }
}