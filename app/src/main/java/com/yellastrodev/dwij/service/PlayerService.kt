package com.yellastrodev.dwij.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.yellastrodev.dwij.activities.MainActivity
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.repo.TrackCacheRepository
import com.yellastrodev.dwij.data.source.YaLazyDataSourceFactory
import com.yellastrodev.dwij.data.source.yandexStreamingTrackId
import com.yellastrodev.dwij.playback.stream.StreamingTrackCache
import com.yellastrodev.dwij.playback.AndroidPlaybackFeedbackAdapter
import com.yellastrodev.dwij.playback.AndroidPlayerListener
import com.yellastrodev.dwij.playback.BluetoothPlaybackResume
import com.yellastrodev.dwij.playback.PlaybackResumeStore
import com.yellastrodev.dwij.playback.AndroidPlaybackRecovery
import com.yellastrodev.dwij.playback.AndroidPlaybackLoadErrorPolicy
import com.yellastrodev.dwij.playback.PlaybackStateStore
import com.yellastrodev.dwij.utils.PlayerEvent
import com.yellastrodev.dwij.utils.PlayerState
import com.yellastrodev.dwij.utils.TrackChangeDirection
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Владеет Media3, кэшем, восстановлением воспроизведения и системной кнопкой лайка. */
@OptIn(UnstableApi::class)
class PlayerService : MediaSessionService() {

    lateinit var player: ExoPlayer
        private set

    private lateinit var mediaSession: MediaSession
    private lateinit var playerListener: AndroidPlayerListener
    private lateinit var streamingCache: StreamingTrackCache
    private lateinit var playbackRecovery: AndroidPlaybackRecovery
    private lateinit var bluetoothPlaybackResume: BluetoothPlaybackResume
    private lateinit var resumeStore: PlaybackResumeStore
    private val likeCommand = SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY)
    private val likeMutationMutex = Mutex()

    private val sessionCallback = object : MediaSession.Callback {
        /** Разрешает системной панели отправлять команду лайка, сохраняя обычные команды. */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            if (controller.isTrusted || session.isMediaNotificationController(controller)) {
                builder.setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                        .buildUpon()
                        .add(likeCommand)
                        .build(),
                )
            }
            return builder.build()
        }

        /** Выполняет лайк только для актуальной песни из Яндекс-фонотеки. */
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != ACTION_TOGGLE_LIKE) {
                return super.onCustomCommand(session, controller, customCommand, args)
            }
            val mediaId = player.currentMediaItem?.mediaId
            serviceScope.launch {
                likeMutationMutex.withLock { toggleCurrentLike(mediaId) }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /** Возвращает сохранённую очередь для Play с гарнитуры или системной панели. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val snapshot = resumeStore.load()
                ?: return Futures.immediateFailedFuture(IllegalStateException("Нет сохранённой очереди"))
            player.shuffleModeEnabled = snapshot.shuffle
            player.repeatMode = snapshot.repeatMode
            Log.d(TAG, "[onPlaybackResumption] Восстановление очереди: size=${snapshot.items.size}, index=${snapshot.index}")
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    snapshot.items,
                    snapshot.index,
                    snapshot.positionMs,
                ),
            )
        }
    }

    private val streamingListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            streamingCache.selectTrack(
                yandexStreamingTrackId(mediaItem?.localConfiguration?.uri?.toString()),
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                streamingCache.stopPlayback()
            }
        }

        /** Сохраняет очередь и позицию при командах внешних MediaController. */
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED) ||
                events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                events.contains(Player.EVENT_POSITION_DISCONTINUITY) ||
                events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)
            ) resumeStore.save(player)
        }
    }

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

    /** Подключает восстановление и системную MediaSession с наблюдением за лайком. */
    override fun onCreate() {
        super.onCreate()

        resumeStore = PlaybackResumeStore(this)

        streamingCache = trackCacheRepo.createStreamingCache()
        val dataSourceFactory = YaLazyDataSourceFactory(this, trackCacheRepo, streamingCache)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(AndroidPlaybackLoadErrorPolicy()),
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(streamingListener)
        bluetoothPlaybackResume = BluetoothPlaybackResume(this, player)
        playbackRecovery = AndroidPlaybackRecovery(
            player = player,
            scope = serviceScope,
            stateStore = stateStore,
            resetSource = streamingCache::stopPlayback,
            finishFeedback = {
                playbackFeedback.onPlaybackEnded(player.currentPosition, player.duration, false)
            },
        )
        player.addListener(playbackRecovery)
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )

        val sessionActivity = PendingIntent.getActivity(
            this,
            PLAYER_ACTIVITY_REQUEST_CODE,
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_PLAYER
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setCallback(sessionCallback)
            .setMediaButtonPreferences(listOf(buildLikeButton(isLiked = false)))
            .build()
        addSession(mediaSession)

        serviceScope.launch {
            applicationComponent.playerRepo.currentSong
                .flatMapLatest { song ->
                    song?.let { applicationComponent.songRepository.song(it.id) }
                        ?: flowOf(null)
                }
                .collectLatest(::updateLikeButton)
        }

        playerListener = AndroidPlayerListener(
            player = player,
            scope = serviceScope,
            stateStore = stateStore,
            feedback = playbackFeedback,
            trackCoverLoader = applicationComponent.trackCoverLoader,
        )
        player.addListener(playerListener)

        serviceScope.launch {
            while (true) {
                delay(5_000L)
                if (player.isPlaying) resumeStore.save(player)
            }
        }

        (application as yApplication)
            .playerServiceRegistry
            .attach(this)

        Log.d(TAG, "[onCreate] Сервис и ExoPlayer созданы")
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo,
    ): MediaSession = mediaSession

    /** Обновляет значок лайка для любого текущего трека. */
    private fun updateLikeButton(song: Song?) {
        val buttons = listOf(buildLikeButton(song?.isLiked == true))
        mediaSession.setMediaButtonPreferences(buttons)
        mediaSession.mediaNotificationControllerInfo?.let { controller ->
            mediaSession.setMediaButtonPreferences(controller, buttons)
        }
    }

    /** Создаёт системную кнопку с актуальным состоянием сердца. */
    private fun buildLikeButton(isLiked: Boolean): CommandButton {
        val icon = if (isLiked) {
            CommandButton.ICON_HEART_FILLED
        } else {
            CommandButton.ICON_HEART_UNFILLED
        }
        val title = if (isLiked) "Убрать лайк" else "Поставить лайк"
        return CommandButton.Builder(icon)
            .setDisplayName(title)
            .setSessionCommand(likeCommand)
            .build()
    }

    /** Повторно сверяет трек и его статус перед изменением Яндекс-плейлиста. */
    private suspend fun toggleCurrentLike(mediaId: String?) {
        val currentSong = applicationComponent.playerRepo.currentSong.value ?: return
        val currentPlayback = applicationComponent.playerRepo.currentPlaybackTrack.value ?: return
        if (mediaId == null || player.currentMediaItem?.mediaId != mediaId ||
            currentPlayback.id != mediaId
        ) return

        val song = applicationComponent.songRepository.song(currentSong.id).first() ?: return
        if (song.isLocalOnlyInLibrary) return
        val yandexTrackId = song.yandexInstances.firstOrNull()?.track?.id ?: return
        if (applicationComponent.playerRepo.currentSong.value?.id != song.id ||
            player.currentMediaItem?.mediaId != mediaId
        ) return

        when (val result = applicationComponent.playlistRepository.setTrackLiked(
            yandexTrackId,
            !song.isLiked,
        )) {
            is DataResult.Failure -> Log.w(
                TAG,
                "[toggleCurrentLike] Не удалось изменить лайк: trackId=$yandexTrackId, error=${result.error}",
            )
            is DataResult.Success -> Unit
        }
    }

    /** Новая очередь отменяет все отложенные повторы прежнего трека. */
    fun playQueue(
        tracks: List<MediaItem>,
        startIndex: Int = 0,
    ) {
        playbackRecovery.onUserCommand()
        stateStore.beginTrackChange(
            direction = TrackChangeDirection.DIRECT,
            wantsToPlay = true,
        )
        player.setMediaItems(tracks, startIndex, 0L)
        resumeStore.save(player)
        player.prepare()
        player.play()
    }

    /**
     * Восстанавливает очередь после пересоздания сервиса без автоматического
     * запуска звука. Исходная пользовательская команда выполняется отдельно.
     */
    fun restoreQueue(
        tracks: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
        durationMs: Long,
        shuffleEnabled: Boolean,
        repeatMode: Int,
    ) {
        if (tracks.isEmpty()) {
            return
        }

        val safeIndex = startIndex.coerceIn(tracks.indices)
        val safePositionMs = startPositionMs.coerceAtLeast(0L)

        playbackRecovery.onUserCommand()
        player.playWhenReady = false
        player.shuffleModeEnabled = shuffleEnabled
        player.repeatMode = repeatMode
        player.setMediaItems(
            tracks,
            safeIndex,
            safePositionMs,
        )
        resumeStore.save(player)
        player.prepare()

        stateStore.setPlayback(
            isPlaying = false,
            currentIndex = safeIndex,
        )
        stateStore.setWantsToPlay(false)
        stateStore.completeTrackChange()
        stateStore.setProgress(
            positionMs = safePositionMs,
            durationMs = durationMs.coerceAtLeast(0L),
        )
        stateStore.setShuffle(shuffleEnabled)
        stateStore.setRepeatAll(repeatMode == Player.REPEAT_MODE_ALL)

        Log.d(
            TAG,
            "[restoreQueue] Очередь восстановлена: size=${tracks.size}, " +
                    "index=$safeIndex, positionMs=$safePositionMs",
        )
    }

    fun addTracks(items: List<MediaItem>) {
        player.addMediaItems(items)
    }

    /** Ручной выбор начинает новую цепочку попыток, включая повторный выбор того же трека. */
    fun playTrack(trackNumber: Int) {
        if (trackNumber !in 0 until player.mediaItemCount) {
            Log.w(TAG, "[playTrack] invalid index=$trackNumber")
            return
        }

        playbackRecovery.onUserCommand()
        stateStore.beginTrackChange(
            direction = TrackChangeDirection.DIRECT,
            wantsToPlay = true,
        )
        player.seekTo(trackNumber, 0L)
        player.playWhenReady = true

        if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }
    }

    fun pause() {
        val wantsToPlay = !player.playWhenReady
        stateStore.setWantsToPlay(wantsToPlay)

        if (wantsToPlay) player.play() else player.pause()
    }

    /** Отменяет восстановление старого трека перед ручным переходом вперёд. */
    fun skipNext() {
        playbackRecovery.onUserCommand()
        val previousIndex = player.currentMediaItemIndex
        stateStore.beginTrackChange(TrackChangeDirection.NEXT)
        player.seekToNext()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()

        if (player.currentMediaItemIndex == previousIndex) {
            stateStore.completeTrackChange()
        }
    }

    /** Отменяет восстановление старого трека перед ручным переходом назад. */
    fun skipPrev() {
        playbackRecovery.onUserCommand()
        val previousIndex = player.currentMediaItemIndex
        stateStore.beginTrackChange(TrackChangeDirection.PREVIOUS)
        player.seekToPrevious()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()

        if (player.currentMediaItemIndex == previousIndex) {
            stateStore.completeTrackChange()
        }
    }

    /** Перемотка пользователя отменяет отложенный запуск с нулевой позиции. */
    fun seekTo(positionMs: Long) {
        playbackRecovery.onUserCommand()
        player.seekTo(positionMs)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    /** Отменяет таймеры восстановления до освобождения плеера и кэша. */
    override fun onDestroy() {
        Log.d(TAG, "[onDestroy] Сервис уничтожается")

        if (::player.isInitialized) resumeStore.save(player)
        if (::bluetoothPlaybackResume.isInitialized) bluetoothPlaybackResume.release()

        if (::playbackRecovery.isInitialized) {
            player.removeListener(playbackRecovery)
            playbackRecovery.release()
        }

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
            player.removeListener(streamingListener)
            player.release()
        }

        if (::streamingCache.isInitialized) {
            streamingCache.close()
        }

        super.onDestroy()
    }

    private companion object {
        const val TAG = "PlayerService"
        const val PLAYER_ACTIVITY_REQUEST_CODE = 1
        const val ACTION_TOGGLE_LIKE = "com.yellastrodev.dwij.TOGGLE_LIKE"
    }
}
