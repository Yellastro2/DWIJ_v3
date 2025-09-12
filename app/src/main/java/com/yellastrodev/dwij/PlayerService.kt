package com.yellastrodev.dwij

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.ui.PlayerNotificationManager
import androidx.media3.session.MediaSession
import androidx.media3.session.legacy.MediaDescriptionCompat
import com.yellastrodev.dwij.activities.MainActivity
import com.yellastrodev.dwij.data.repo.AlbumCoverRepository
import com.yellastrodev.dwij.data.repo.TrackRepository
import com.yellastrodev.yandexmusiclib.entities.CoverSize
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

@UnstableApi
class PlayerService : Service() {

//    private val app by lazy { application as yApplication }
//    private val trackRepo by lazy { app.trackRepository }
//    private val playlistRepo by lazy { app.playlistRepository }
//    private val playerRepo by lazy { app.playerRepo }

    private lateinit var player: ExoPlayer
    private val binder = PlayerBinder()

    private val coverRepo: AlbumCoverRepository by lazy {
        (application as yApplication).albumCoverRepository
    }

    private val trackRepo: TrackRepository by lazy {
        (application as yApplication).trackRepository
    }


    // Горячие стримы состояния
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    override fun onCreate() {
        super.onCreate()
        сreateChannel()
        player = ExoPlayer.Builder(this).build()

        // Пример: слушаем события плеера
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.value = _state.value.copy(
                    isPlaying = player.isPlaying,
                    currentIndex = player.currentMediaItemIndex
                )
            }
        })

        val notification = NotificationCompat.Builder(this, "player_channel")
            .setContentTitle("Загрузка плеера…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        startForegroundWithNotification()
    }

    // Команды управления
    fun playQueue(tracks: List<MediaItem>, startIndex: Int = 0) {

        player.setMediaItems(tracks, startIndex, 0)
        player.prepare()
        player.play()
    }

    fun pause() = player.pause()
    fun resume() = player.play()
    fun skipNext() = player.seekToNext()
    fun skipPrev() = player.seekToPrevious()

    override fun onBind(intent: Intent): IBinder {
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
        notificationManager = PlayerNotificationManager.Builder(
            this,
            NOTIFICATION_ID,
            "player_channel"
        ).setMediaDescriptionAdapter(object : PlayerNotificationManager.MediaDescriptionAdapter {
            override fun getCurrentContentTitle(player: Player): CharSequence {
                return player.currentMediaItem?.mediaMetadata?.title ?: "Unknown"
            }

            override fun createCurrentContentIntent(player: Player): PendingIntent? {
                return PendingIntent.getActivity(
                    this@PlayerService,
                    0,
                    Intent(this@PlayerService, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            }

            override fun getCurrentContentText(player: Player): CharSequence? {
                return player.currentMediaItem?.mediaMetadata?.artist
            }

            private var coverJob: Job? = null

            override fun getCurrentLargeIcon(
                player: Player,
                callback: PlayerNotificationManager.BitmapCallback
            ): Bitmap? {
                // Отменяем предыдущую загрузку
                coverJob?.cancel()

                coverJob = GlobalScope.launch {

                    val coverBitmap: Bitmap? = getCurrentTrackCoverBitmap()
                    if (coverBitmap != null) {
                        withContext(Dispatchers.Main) {
                            callback.onBitmap(coverBitmap)
                        }
                    }
                }



                return null // возвращаем null, чтобы менеджер использовал callback
            }

        }).setNotificationListener(object : PlayerNotificationManager.NotificationListener {
            override fun onNotificationPosted(
                notificationId: Int,
                notification: android.app.Notification,
                ongoing: Boolean
            ) {
                // вот тут стартуем foreground
                startForeground(notificationId, notification)
            }

            override fun onNotificationCancelled(
                notificationId: Int,
                dismissedByUser: Boolean
            ) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }).build()

        notificationManager.setPlayer(player)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaSession.release()
        player.release()
    }

    // Пример функции для получения Bitmap обложки текущего трека
    private suspend fun getCurrentTrackCoverBitmap(): Bitmap? {
        val mediaItem = withContext(Dispatchers.Main) {
             player.currentMediaItem ?: return@withContext null
        }
        val trackId = mediaItem?.mediaMetadata?.extras?.getString("track_id") ?: return null
        val track = trackRepo.tracks.value[trackId]!!
        // асинхронно/кэшированный репо запрос
        return coverRepo.getCover(track, CoverSize.`100x100`)
    }

    fun сreateChannel(){
        val channel = NotificationChannel(
            "player_channel",
            "Media playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Media playback controls"
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}

data class PlayerState(
    val isPlaying: Boolean = false,
    val currentIndex: Int = 0
)
