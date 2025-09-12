package com.yellastrodev.dwij.service

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerNotificationManager
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.yellastrodev.dwij.data.repo.CoverRepository
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.data.repo.TrackCacheRepository
import com.yellastrodev.dwij.data.repo.TrackRepository
import com.yellastrodev.dwij.data.source.YaLazyDataSourceFactory
import com.yellastrodev.dwij.data.source.YaTrackMediaSourceFactory
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val TAG = "PlayerService"

@UnstableApi
class PlayerService : Service() {

    private lateinit var player: ExoPlayer
    private val binder = PlayerBinder()

    // Репозитории для треков и обложек
    internal val coverRepo: CoverRepository by lazy {
        (application as yApplication).coverRepository
    }
    internal val trackRepo: TrackRepository by lazy {
        (application as yApplication).trackRepository
    }
    val playerRepo: PlayerRepository by lazy {
        (application as yApplication).playerRepo
    }
    val trackCacheRepo: TrackCacheRepository by lazy {
        (application as yApplication).trackCacheRepo
    }

    /** Горячие стримы состояния плеера для UI или наблюдения */
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state



    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called: создаем канал и плеер")
        createChannel()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                5_000,   // minBufferMs
                10_000,  // maxBufferMs
                1_000,   // bufferForPlaybackMs
                2_000    // bufferForPlaybackAfterRebufferMs
            )
            .build()

        val dataSourceFactory = YaLazyDataSourceFactory(this, trackCacheRepo)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

//        player = ExoPlayer.Builder(this)
//            .setLoadControl(loadControl)
//            .setMediaSourceFactory(YaTrackMediaSourceFactory(trackCacheRepo))
//            .build()
        Log.d(TAG, "ExoPlayer инициализирован")

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val base = super.onConnect(session, controller)
                    val customCommands = base.availableSessionCommands
                    val customPlayerCommands = base.availablePlayerCommands
                        .buildUpon()
                        .add(Player.COMMAND_SEEK_TO_NEXT)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                        .build()

                    return MediaSession.ConnectionResult.accept(
                        customCommands,
                        customPlayerCommands
                    )
                }
            })
            .build()




        // Слушаем изменения состояния воспроизведения
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.value = _state.value.copy(
                    isPlaying = player.isPlaying,
                    currentIndex = player.currentMediaItemIndex
                )
                if (playbackState == Player.STATE_ENDED) {
                    GlobalScope.launch {
                        playerRepo.skipNext()
                    }
                }
                Log.d(TAG, "PlaybackStateChanged: isPlaying=${player.isPlaying}, index=${player.currentMediaItemIndex}")
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)

                if (isPlaying) startProgressUpdates() else stopProgressUpdates()
                Log.d(TAG, "onIsPlayingChanged: $isPlaying")
            }

        })

        // Минимальное уведомление пока плеер готовится
        val notification = NotificationCompat.Builder(this, "player_channel")
            .setContentTitle("Загрузка плеера…")
            .setSmallIcon(R.drawable.ic_media_play)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Foreground запущен с временным уведомлением")

        startForegroundWithNotification()
    }

    private var progressJob: Job? = null

    fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                val pos = player.currentPosition
                val dur = player.duration
                _state.value = _state.value.copy(currentPosition = pos, duration = dur)
                delay(500L) // обновляем каждые 500 мс
            }
        }
    }

    fun stopProgressUpdates() {
        progressJob?.cancel()
    }

    /** Воспроизвести очередь треков с указанного индекса */
    fun playQueue(tracks: List<MediaItem>, startIndex: Int = 0) {
        Log.d(TAG, "playQueue called: startIndex=$startIndex, tracks=${tracks.size}")
        player.setMediaItems(tracks, startIndex, 0)
        player.prepare()
        player.play()
    }

    fun playTrack(track: MediaItem) {
        Log.d(TAG, "playTrack called: track=$track")
        player.clearMediaItems()
        player.setMediaItem(track)

//        // Заглушка предыдущего трека (можно silent файл)
//        val prevPlaceholder = MediaItem.Builder()
//            .setUri("android.resource://your.package.name/raw/silent")
//            .setMediaMetadata(MediaMetadata.Builder()
//                .setTitle("Loading...")
//                .build())
//            .build()
//
//        // Заглушка следующего трека
//        val nextPlaceholder = MediaItem.Builder()
//            .setUri("android.resource://your.package.name/raw/silent")
//            .setMediaMetadata(MediaMetadata.Builder()
//                .setTitle("Loading...")
//                .build())
//            .build()
//
//        // Добавляем в правильном порядке: prev, current, next
//        player.addMediaItem(prevPlaceholder)
//        player.addMediaItem(track)
//        player.addMediaItem(nextPlaceholder)
//
//        // Ставим индекс на текущий трек
//        player.seekTo(1, 0L)
        player.prepare()
        player.play()
    }



    /**
     * пауза если играет, иначе плей
     */
    fun pause() {
        if (player.isPlaying) {
            player.pause().also { Log.d(TAG, "pause called") }
        } else {
            player.play().also { Log.d(TAG, "resume called") }
        }
    }
//    fun resume() = player.play().also { Log.d(TAG, "resume called") }
    fun skipNext() = player.seekToNext().also { Log.d(TAG, "skipNext called") }
    fun skipPrev() = player.seekToPrevious().also { Log.d(TAG, "skipPrev called") }

    override fun onBind(intent: Intent): IBinder {
        Log.d(TAG, "Service bound")
        return binder
    }

    inner class PlayerBinder : Binder() {
        fun getService(): PlayerService = this@PlayerService
    }

    private lateinit var mediaSession: MediaSession
    private lateinit var notificationManager: PlayerNotificationManager
    val NOTIFICATION_ID = 1525343

    @OptIn(DelicateCoroutinesApi::class)
    private fun startForegroundWithNotification() {
        val customReceiver = object : PlayerNotificationManager.CustomActionReceiver {
            override fun createCustomActions(context: Context, instanceId: Int) =
                mapOf(
                    "ACTION_NEXT" to NotificationCompat.Action(
                        R.drawable.ic_media_next, "Next", createPendingIntent(context, "ACTION_NEXT")
                    ),
                    "ACTION_PREV" to NotificationCompat.Action(
                        R.drawable.ic_media_previous, "Prev", createPendingIntent(context, "ACTION_PREV")
                    )
                )

            private fun createPendingIntent(
                context: Context,
                string: String
            ): PendingIntent? {
                Log.d(TAG, "createPendingIntent called: string=$string")
                return null
            }

            override fun getCustomActions(player: Player) =
                listOf("ACTION_PREV", "ACTION_NEXT") // всегда показывать

            override fun onCustomAction(player: Player, action: String, intent: Intent) {
                when (action) {
                    "ACTION_NEXT" -> skipNextEvenIfSingle()
                    "ACTION_PREV" -> skipPrevEvenIfSingle()
                }
            }

            private fun skipPrevEvenIfSingle() {
                TODO("Not yet implemented")
            }

            private fun skipNextEvenIfSingle() {
                TODO("Not yet implemented")
            }
        }
        Log.d(TAG, "startForegroundWithNotification called")
        notificationManager = PlayerNotificationManager.Builder(
            this,
            NOTIFICATION_ID,
            "player_channel"
        )   .setMediaDescriptionAdapter(yPushMediaAdapterobject(this))
            .setNotificationListener(object : PlayerNotificationManager.NotificationListener {
            override fun onNotificationPosted(
                notificationId: Int,
                notification: Notification,
                ongoing: Boolean
            ) {
                Log.d(TAG, "Notification posted: id=$notificationId, ongoing=$ongoing")
                startForeground(notificationId, notification)
            }

            override fun onNotificationCancelled(
                notificationId: Int,
                dismissedByUser: Boolean
            ) {
                Log.d(TAG, "Notification cancelled: id=$notificationId, dismissedByUser=$dismissedByUser")
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        })
//            .setCustomActionReceiver(customReceiver)
            .build()

//        startForeground(NOTIFICATION_ID, notification)
        notificationManager.setPlayer(player)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        mediaSession.release()
        player.release()
    }

    /** Создает канал уведомлений для плеера */
    fun createChannel(){
        val channel = NotificationChannel(
            "player_channel",
            "Media playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Media playback controls"
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        Log.d(TAG, "Notification channel created: player_channel")
    }

    fun seekTo(lng: Long) {
        player.seekTo(lng)
    }


}

data class PlayerState(
    val isPlaying: Boolean = false,
    val currentIndex: Int = 0,
    val currentPosition: Long = 0,
    val duration: Long = 0
)
