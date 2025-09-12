package com.yellastrodev.dwij

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerNotificationManager
import androidx.media3.session.MediaSession
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

private const val TAG = "PlayerService"

@UnstableApi
class PlayerService : Service() {

    private lateinit var player: ExoPlayer
    private val binder = PlayerBinder()

    // Репозитории для треков и обложек
    private val coverRepo: AlbumCoverRepository by lazy {
        (application as yApplication).albumCoverRepository
    }

    private val trackRepo: TrackRepository by lazy {
        (application as yApplication).trackRepository
    }

    /** Горячие стримы состояния плеера для UI или наблюдения */
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called: создаем канал и плеер")
        сreateChannel()

        player = ExoPlayer.Builder(this).build()
        Log.d(TAG, "ExoPlayer инициализирован")

        // Слушаем изменения состояния воспроизведения
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.value = _state.value.copy(
                    isPlaying = player.isPlaying,
                    currentIndex = player.currentMediaItemIndex
                )
                Log.d(TAG, "PlaybackStateChanged: isPlaying=${player.isPlaying}, index=${player.currentMediaItemIndex}")
            }
        })

        // Минимальное уведомление пока плеер готовится
        val notification = NotificationCompat.Builder(this, "player_channel")
            .setContentTitle("Загрузка плеера…")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Foreground запущен с временным уведомлением")

        startForegroundWithNotification()
    }

    /** Воспроизвести очередь треков с указанного индекса */
    fun playQueue(tracks: List<MediaItem>, startIndex: Int = 0) {
        Log.d(TAG, "playQueue called: startIndex=$startIndex, tracks=${tracks.size}")
        player.setMediaItems(tracks, startIndex, 0)
        player.prepare()
        player.play()
    }

    fun pause() = player.pause().also { Log.d(TAG, "pause called") }
    fun resume() = player.play().also { Log.d(TAG, "resume called") }
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
        Log.d(TAG, "startForegroundWithNotification called")
        notificationManager = PlayerNotificationManager.Builder(
            this,
            NOTIFICATION_ID,
            "player_channel"
        ).setMediaDescriptionAdapter(object : PlayerNotificationManager.MediaDescriptionAdapter {

            private var coverJob: Job? = null
            // Внутри PlayerService
            private val coverCache = LinkedHashMap<String, Bitmap>(5, 0.75f, true) // LRU с порядком доступа
            private val COVER_CACHE_LIMIT = 5

            override fun getCurrentContentTitle(player: Player): CharSequence {
                val title = player.currentMediaItem?.mediaMetadata?.title ?: "Unknown"
                Log.d(TAG, "getCurrentContentTitle: $title")
                return title
            }

            override fun createCurrentContentIntent(player: Player): PendingIntent? {
                Log.d(TAG, "createCurrentContentIntent called")
                return PendingIntent.getActivity(
                    this@PlayerService,
                    0,
                    Intent(this@PlayerService, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            }

            override fun getCurrentContentText(player: Player): CharSequence? {
                val artist = player.currentMediaItem?.mediaMetadata?.artist
                Log.d(TAG, "getCurrentContentText: $artist")
                return artist
            }

            override fun getCurrentLargeIcon(
                player: Player,
                callback: PlayerNotificationManager.BitmapCallback
            ): Bitmap? {
                // Отменяем предыдущую загрузку
                coverJob?.cancel()
                val mediaItem = player.currentMediaItem
                val trackId = mediaItem?.mediaMetadata?.extras?.getString("track_id")
                Log.d(TAG, "getCurrentLargeIcon called: trackId=$trackId")

                // Проверяем кеш
                coverCache[trackId]?.let {
                    Log.d(TAG, "Cache hit for trackId=$trackId")
                    callback.onBitmap(it)
                    return it
                }

                coverJob = GlobalScope.launch {
                    val bitmap = getCurrentTrackCoverBitmap(trackId!!)
                    if (bitmap != null) {
                        withContext(Dispatchers.Main) {
                            Log.d(TAG, "onBitmap callback отправлен для trackId=$trackId")
                            callback.onBitmap(bitmap)
                        }
                        // Добавляем в кеш с ограничением размера
                        synchronized(coverCache) {
                            if (coverCache.size >= COVER_CACHE_LIMIT) {
                                val firstKey = coverCache.keys.first()
                                coverCache.remove(firstKey)
                            }
                            coverCache[trackId] = bitmap
                        }
                    } else {
                        Log.d(TAG, "Cover bitmap null for trackId=$trackId")
                    }
                }

                return null // уведомление подождёт callback
            }

        }).setNotificationListener(object : PlayerNotificationManager.NotificationListener {
            override fun onNotificationPosted(
                notificationId: Int,
                notification: android.app.Notification,
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
        }).build()

        notificationManager.setPlayer(player)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        mediaSession.release()
        player.release()
    }

    /**
     * Загружает обложку текущего трека через AlbumCoverRepository
     * @return Bitmap текущей обложки или null
     */
    private suspend fun getCurrentTrackCoverBitmap(trackId: String): Bitmap? {

        Log.d(TAG, "getCurrentTrackCoverBitmap: trackId=$trackId")
        val track = trackId?.let { trackRepo.tracks.value[it] } ?: return null
        Log.d(TAG, "Track found in repo: $track")
        return coverRepo.getCover(track, CoverSize.`100x100`).also {
            Log.d(TAG, "Bitmap loaded for trackId=$trackId, size=${it.width}x${it.height}")
        }
    }

    /** Создает канал уведомлений для плеера */
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
        Log.d(TAG, "Notification channel created: player_channel")
    }
}

data class PlayerState(
    val isPlaying: Boolean = false,
    val currentIndex: Int = 0
)
