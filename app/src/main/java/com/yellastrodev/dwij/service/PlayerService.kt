package com.yellastrodev.dwij.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Binder
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaNotification
import androidx.media3.ui.PlayerNotificationManager
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.google.common.collect.ImmutableList
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.repo.CoverRepository
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.data.repo.TrackCacheRepository
import com.yellastrodev.dwij.data.repo.TrackRepository
import com.yellastrodev.dwij.data.source.YaLazyDataSourceFactory
import com.yellastrodev.dwij.playback.AndroidPlaybackFeedbackAdapter
import com.yellastrodev.dwij.playback.feedback.PlaybackMetadataKeys
import com.yellastrodev.dwij.utils.PlayerEvent
import com.yellastrodev.dwij.utils.PlayerState
import com.yellastrodev.dwij.yApplication
import com.yellastrodev.yandexmusiclib.entities.CoverSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference

private const val TAG = "PlayerService"

class PlayerService : MediaSessionService() {

    lateinit var player: ExoPlayer
    lateinit var mediaSession: MediaSession
    private var selfController: MediaController? = null
    private val binder = PlayerBinder()
    internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Репозитории для треков и обложек
    private val applicationComponent by lazy {
        (application as yApplication).component
    }

    // Репозитории для треков и обложек
    internal val coverRepo: CoverRepository by lazy {
        applicationComponent.coverRepository
    }

    internal val trackRepo: TrackRepository by lazy {
        applicationComponent.trackRepository
    }

    val playerRepo: PlayerRepository by lazy {
        applicationComponent.playerRepo
    }

    val trackCacheRepo: TrackCacheRepository by lazy {
        applicationComponent.trackCacheRepo
    }

    private val playbackFeedbackTracker: AndroidPlaybackFeedbackAdapter by lazy {
        AndroidPlaybackFeedbackAdapter(
            tracker = applicationComponent.playbackFeedbackTracker,
        )
    }

    /** Горячие стримы состояния плеера для UI или наблюдения */
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private val _events = MutableSharedFlow<PlayerEvent>()
    val events: SharedFlow<PlayerEvent> = _events

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Log.d(TAG, "[onStartCommand] Получена команда запуска")
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate called: создаем канал и плеер")
//        createChannel()
//
//        val contentIntent: PendingIntent = PendingIntent.getActivity(
//            this@PlayerService,
//            0,
//            Intent(this, MainActivity::class.java).apply {
//                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
//            },
//            PendingIntent.FLAG_IMMUTABLE
//        )
//
//        // Минимальное уведомление пока плеер готовится
//        val notification = NotificationCompat.Builder(this, "player_channel")
//            .setContentTitle("Загрузка плеера…")
//            .setSmallIcon(R.drawable.ic_logo_dance_monochrom)
//            .setContentIntent(contentIntent)
//            .build()
//
//        startForeground(NOTIFICATION_ID, notification)
//        Log.d(TAG, "Foreground запущен с временным уведомлением")

        val dataSourceFactory = YaLazyDataSourceFactory(this, trackCacheRepo)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

//        startForegroundWithNotification()

//        player = ExoPlayer.Builder(this)
//            .setLoadControl(loadControl)
//            .setMediaSourceFactory(YaTrackMediaSourceFactory(trackCacheRepo))
//            .build()
        Log.d(TAG, "ExoPlayer инициализирован")

        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true
        )


        mediaSession = MediaSession.Builder(this, player)

            .setCallback(object : MediaSession.Callback {
//                override fun onConnect(
//                    session: MediaSession,
//                    controller: MediaSession.ControllerInfo
//                ): MediaSession.ConnectionResult {
//                    val base = super.onConnect(session, controller)
//                    val customCommands = base.availableSessionCommands
//                    val customPlayerCommands = base.availablePlayerCommands
//                        .buildUpon()
//                        .add(Player.COMMAND_SEEK_TO_NEXT)
//                        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
//                        .build()
//
//                    return MediaSession.ConnectionResult.accept(
//                        customCommands,
//                        customPlayerCommands
//                    )
//                }
            })
            .build()

        // Слушаем изменения состояния воспроизведения
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                playbackFeedbackTracker.onMediaItemTransition(
                    mediaItem = mediaItem,
                    reason = reason,
                    isPlaying = player.isPlaying,
                    currentPositionMs = player.currentPosition,
                    durationMs = player.duration
                )
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    Log.d(TAG, "Автопереход на следующий трек: $mediaItem")
                    _state.value = _state.value.copy(
                        currentIndex = player.currentMediaItemIndex
                    )
                }
                if (mediaItem == null) return
                val trackId = mediaItem.mediaId
                if (
                    mediaItem.mediaMetadata.extras?.getString(
                        PlaybackMetadataKeys.MUSIC_SOURCE,
                    ) == PlaybackMetadataKeys.SOURCE_LOCAL
                ) {
                    Log.d(TAG, "[onMediaItemTransition] Локальный трек, сетевую обложку не загружаем")
                    return
                }
                // грузим обложку из coverRepo
                serviceScope.launch(Dispatchers.IO) {

                    val currentMetadata = mediaItem.mediaMetadata

                    // Проверяем: если уже есть обложка, ничего не делаем
                    if (currentMetadata.artworkData != null || currentMetadata.artworkUri != null) {
                        Log.d(TAG, "У mediaItem уже есть обложка, пропускаем загрузку")
                        return@launch
                    }

                    val track = when (val result = trackRepo.getTrack(trackId)) {
                        is DataResult.Success -> result.value
                        is DataResult.Failure -> {
                            Log.w(
                                TAG,
                                "[onMediaItemTransition] Метаданные трека не загружены: " +
                                    result.error
                            )
                            return@launch
                        }
                    }
                    val coverData =
                        coverRepo.getPlayerTrackCover(track)
                            ?: return@launch

                    val newMetadata = mediaItem.mediaMetadata.buildUpon()
                        .setArtworkData(
                            coverData.bytes,
                            MediaMetadata.PICTURE_TYPE_FRONT_COVER,
                        )
                        .build()

                    val newItem = mediaItem.buildUpon()
                        .setMediaMetadata(newMetadata)
                        .build()

                    withContext(Dispatchers.Main) {
                        val index = player.currentMediaItemIndex

                        if (index != C.INDEX_UNSET) {
                            player.replaceMediaItem(index, newItem)
                        }
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.value = _state.value.copy(
                    isPlaying = player.isPlaying,
                    currentIndex = player.currentMediaItemIndex
                )
                if (playbackState == Player.STATE_ENDED) {
                    playbackFeedbackTracker.onPlaybackEnded(
                        currentPositionMs = player.currentPosition,
                        durationMs = player.duration,
                        completed = true
                    )
                    if (player.currentMediaItemIndex == player.mediaItemCount - 1 &&
                        player.repeatMode == Player.REPEAT_MODE_OFF
                    ) {
                        // Здесь точно закончился весь список, повтор выключен
                        onPlaylistFinished()
                    } else
                        serviceScope.launch {
                            playerRepo.skipNext()
                        }
                }
                Log.d(TAG, "PlaybackStateChanged: isPlaying=${player.isPlaying}, index=${player.currentMediaItemIndex}")
            }



            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)

                playbackFeedbackTracker.onIsPlayingChanged(
                    mediaItem = player.currentMediaItem,
                    isPlaying = isPlaying,
                    currentPositionMs = player.currentPosition,
                    durationMs = player.duration
                )

                if (isPlaying) startProgressUpdates() else stopProgressUpdates()
                Log.d(TAG, "onIsPlayingChanged: $isPlaying")
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackFeedbackTracker.onPlaybackEnded(
                    currentPositionMs = player.currentPosition,
                    durationMs = player.duration,
                    completed = false
                )
                Log.e(
                    TAG,
                    "[onPlayerError] Ошибка проигрывания, code=${error.errorCode}",
                    error
                )
                serviceScope.launch {
                    _events.emit(PlayerEvent.ShowError("Ошибка воспроизведения"))
                }
                player.seekToNext() // просто перескакиваем
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _state.value = _state.value.copy(isShuffle = shuffleModeEnabled)
                Log.d(TAG, "ShuffleModeChanged: $shuffleModeEnabled")
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _state.value = _state.value.copy(
                    isRepeatAll = (repeatMode == Player.REPEAT_MODE_ALL)
                )
                Log.d(TAG, "RepeatModeChanged: $repeatMode")
            }
        })

        // костыль: создаём контроллер на свой же сервис
        val sessionToken = SessionToken(this, ComponentName(this, PlayerService::class.java))

        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener(
            {
                try {
                    selfController = controllerFuture.get()
                    Log.d(TAG, "Self MediaController создан")
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка при создании selfController", e)
                }
            },
            ContextCompat.getMainExecutor(this)
        )

//        setMediaNotificationProvider(MyNotificationProvider(this))

        (application as yApplication)
            .playerServiceRegistry
            .attach(this)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        Log.d(TAG, "onGetSession called")
        return mediaSession
    }

    private var progressJob: Job? = null

    fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            while (true) {
                val pos = player.currentPosition
                val dur = player.duration
                _state.value = _state.value.copy(currentPosition = pos, duration = dur)
                playbackFeedbackTracker.onProgress(
                    trackId = player.currentMediaItem?.mediaId,
                    currentPositionMs = pos,
                    durationMs = dur
                )
                delay(500L) // обновляем каждые 500 мс
            }
        }
    }

    fun stopProgressUpdates() {
        progressJob?.cancel()
    }

    private suspend fun waitForController(): MediaController {
        while (selfController == null) {
            delay(50)
        }
        return selfController!!
    }

    /** Воспроизвести очередь треков с указанного индекса */
    suspend fun playQueue(tracks: List<MediaItem>, startIndex: Int = 0) {
        Log.d(TAG, "playQueue called: startIndex=$startIndex, tracks=${tracks.size}")
//        player.setMediaItems(tracks, startIndex, 0)
//        player.prepare()
//        player.play()
        val controller = waitForController() // ждём пока selfController != null

        controller.setMediaItems(tracks, startIndex, 0)
        controller.prepare()
        controller.play()
    }

    fun addTrack(track: MediaItem) {
        Log.d(TAG, "addTrack called: track=${track.toString()}")
        player.addMediaItem(track)
    }


    fun addTracks(items: List<MediaItem>) {
        Log.d(TAG, "addTracks called: tracks=${items.size}")
        player.addMediaItems(items)
    }

    /**
     * Воспроизвести конкретный трек из текущего списка загруженного в плеер.
     */
    fun playTrack(trackNumber: Int){
        if (trackNumber in 0 until player.mediaItemCount) {
            player.seekTo(trackNumber, 0L)
            player.playWhenReady = true
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            Log.d(TAG, "playTrack: switched to index=$trackNumber")
        } else {
            Log.w(TAG, "playTrack: invalid index $trackNumber, total=${player.mediaItemCount}")
        }
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

    private fun onPlaylistFinished() {
        Log.d(TAG, "Playlist finished")
        serviceScope.launch {
            _events.emit(PlayerEvent.TrackListEnd("Playlist finished"))
        }
    }

//    override fun onBind(intent: Intent): IBinder {
//        Log.d(TAG, "Service bound")
//        return binder
//    }

    inner class PlayerBinder : Binder() {
        fun getService(): PlayerService = this@PlayerService
    }

    private lateinit var notificationManager: PlayerNotificationManager
    val NOTIFICATION_ID = 1525343

    private fun startForegroundWithNotification() {
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
        if (::player.isInitialized) {
            playbackFeedbackTracker.onPlaybackEnded(
                currentPositionMs = player.currentPosition,
                durationMs = player.duration,
                completed = false
            )
        }
        Log.d(TAG, "[onDestroy] Сервис плеера уничтожен")
        (application as yApplication)
            .playerServiceRegistry
            .detach(this)
        serviceScope.cancel()
        mediaSession.release()
        player.release()
        super.onDestroy()
    }

    /** Создает канал уведомлений для плеера */
    fun createChannel(){
        val channel = NotificationChannel(
            "player_channel",
            "D W I J player",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Media playback controls"
            setSound(null, null)       // отключить звук
            enableVibration(false)     // отключить вибрацию
            setShowBadge(false)        // не показывать бейдж
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        Log.d(TAG, "Notification channel created: player_channel")
    }

    fun seekTo(lng: Long) {
        player.seekTo(lng)
    }


//    fun shuffle() {
//        player.shuffleModeEnabled = !player.shuffleModeEnabled
//    }


    class MyNotificationProvider(private val context: Context) : MediaNotification.Provider {

        // создаём дефолтный провайдер внутри
        private val defaultProvider = DefaultMediaNotificationProvider(context)

        override fun createNotification(
            mediaSession: MediaSession,
            customLayout: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            onNotificationChangedCallback: MediaNotification.Provider.Callback
        ): MediaNotification {
            // получаем дефолтное уведомление
            val defaultNotification = defaultProvider.createNotification(
                mediaSession,
                customLayout,
                actionFactory,
                onNotificationChangedCallback
            )

            val original = defaultNotification.notification

            // клонируем готовое уведомление и меняем только smallIcon
            val newNotification = NotificationCompat.Builder(context, original.channelId)
                .setSmallIcon(R.drawable.ic_logo_dance_monochrom)
                .setContentIntent(original.contentIntent)
                .setCustomContentView(original.contentView)
                .setCustomBigContentView(original.bigContentView)
                .setCustomHeadsUpContentView(original.headsUpContentView)
                .build()

            return MediaNotification(defaultNotification.notificationId, newNotification)
        }

        override fun handleCustomCommand(
            session: MediaSession,
            action: String,
            extras: Bundle
        ): Boolean {
            return false
        }
    }

}







