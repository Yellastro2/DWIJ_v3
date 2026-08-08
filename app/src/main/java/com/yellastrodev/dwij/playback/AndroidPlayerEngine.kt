package com.yellastrodev.dwij.playback

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

import com.yellastrodev.dwij.data.entities.PlaybackTrack
import com.yellastrodev.dwij.data.entities.dTracklist
import com.yellastrodev.dwij.service.PlayerService
import com.yellastrodev.dwij.utils.PlayerEvent
import com.yellastrodev.dwij.utils.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidPlayerEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val mediaItemMapper: AndroidMediaItemMapper,
    private val serviceRegistry:
        AndroidPlayerServiceRegistry,
) : PlayerEngine {

    private val applicationContext = context.applicationContext

    private var service: PlayerService? = null

    /**
     * Последняя очередь хранится в application-scoped engine и переживает
     * уничтожение отдельного экземпляра PlayerService.
     */
    @Volatile
    private var playbackSnapshot: PlaybackSnapshot? = null

    @Volatile
    private var shuffleEnabled = false

    @Volatile
    private var repeatMode = Player.REPEAT_MODE_OFF

    /**
     * Не даёт двум одновременным вызовам prepare()
     * параллельно запускать сервис и создавать двойные подписки.
     */
    private val prepareMutex = Mutex()

    /**
     * Флаг относится только к текущему экземпляру service.
     *
     * Когда PlayerService пересоздаётся, флаг сбрасывается.
     */
    private var subscriptionsStarted = false

    private var stateSubscriptionJob: Job? = null
    private var eventsSubscriptionJob: Job? = null

    private val mutableState = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<PlayerEvent>()
    override val events: SharedFlow<PlayerEvent> = mutableEvents.asSharedFlow()

    init {
        serviceRegistry.setServiceAttachedListener(
            ::restoreSnapshotIfNeeded,
        )
    }

    override suspend fun prepare() {
        prepareMutex.withLock {
            /*
             * Возможно, сервис уже был получен раньше.
             * Проверяем, что ссылка всё ещё соответствует живому сервису.
             */
            val currentApplicationService = getApplicationService()

            if (
                service != null &&
                service === currentApplicationService
            ) {
                /*
                 * Сервис тот же самый.
                 * Но подписки всё равно проверяем: теоретически они могли
                 * быть отменены отдельно.
                 */
                startSubscriptionsIfNeeded(
                    playerService = requireNotNull(service),
                )
                return
            }

            /*
             * Либо сервиса ещё не было, либо старый экземпляр уничтожен.
             */
            startPlayerService()

            val newService = waitForService()

            /*
             * Если появился другой экземпляр PlayerService,
             * старые подписки больше не нужны.
             */
            if (service !== newService) {
                stopSubscriptions()

                service = newService
                subscriptionsStarted = false
            }

            startSubscriptionsIfNeeded(
                playerService = newService,
            )
        }
    }

    private fun restoreSnapshotIfNeeded(
        playerService: PlayerService,
    ) {
        val snapshot = playbackSnapshot ?: return
        val currentState = mutableState.value

        playerService.restoreQueue(
            tracks = snapshot.items,
            startIndex = currentState.currentIndex,
            startPositionMs = currentState.currentPosition,
            durationMs = currentState.duration,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
        )

        Log.d(
            TAG,
            "[restoreSnapshotIfNeeded] Состояние передано новому сервису: " +
                    "size=${snapshot.items.size}, " +
                    "index=${currentState.currentIndex}, " +
                    "positionMs=${currentState.currentPosition}",
        )
    }

    private fun startPlayerService() {
        val intent = Intent(
            applicationContext,
            PlayerService::class.java,
        )

        applicationContext.startService(intent)
    }

    private suspend fun waitForService(): PlayerService {
        while (true) {
            getApplicationService()?.let { playerService ->
                return playerService
            }

            delay(SERVICE_CHECK_DELAY_MS)
        }
    }

    private fun getApplicationService():
        PlayerService? =
        serviceRegistry.current()

    /**
     * Вот здесь конкретно должна находиться проверка subscriptionsStarted.
     *
     * Проверка выполняется после того, как service уже получен,
     * и непосредственно перед launchIn().
     */
    private fun startSubscriptionsIfNeeded(
        playerService: PlayerService,
    ) {
        if (subscriptionsStarted) {
            return
        }

        subscriptionsStarted = true

        stateSubscriptionJob = playerService.state
            .onEach { playerState ->
                shuffleEnabled = playerState.isShuffle
                repeatMode = if (playerState.isRepeatAll) {
                    Player.REPEAT_MODE_ALL
                } else {
                    Player.REPEAT_MODE_OFF
                }
                mutableState.value = playerState
            }
            .launchIn(scope)

        eventsSubscriptionJob = playerService.events
            .onEach { event ->
                mutableEvents.emit(event)
            }
            .launchIn(scope)
    }

    private fun stopSubscriptions() {
        stateSubscriptionJob?.cancel()
        stateSubscriptionJob = null

        eventsSubscriptionJob?.cancel()
        eventsSubscriptionJob = null

        subscriptionsStarted = false
    }

    override suspend fun setQueue(
        tracks: List<PlaybackTrack>,
        startIndex: Int,
        tracklist: dTracklist?,
    ) {
        prepare()

        val mediaItems = tracks.map { track ->
            mediaItemMapper.map(
                track = track,
                tracklist = tracklist,
            )
        }

        playbackSnapshot = PlaybackSnapshot(
            items = mediaItems,
        )

        withContext(Dispatchers.Main) {
            requireNotNull(service).playQueue(
                tracks = mediaItems,
                startIndex = startIndex,
            )
        }
    }

    override suspend fun appendTracks(
        tracks: List<PlaybackTrack>,
        tracklist: dTracklist?,
    ) {
        prepare()

        val mediaItems = tracks.map { track ->
            mediaItemMapper.map(
                track = track,
                tracklist = tracklist,
            )
        }

        playbackSnapshot = PlaybackSnapshot(
            items = playbackSnapshot?.items.orEmpty() + mediaItems,
        )

        withContext(Dispatchers.Main) {
            requireNotNull(service).addTracks(mediaItems)
        }
    }

    override suspend fun playTrack(index: Int) {
        prepare()

        withContext(Dispatchers.Main) {
            requireNotNull(service).playTrack(index)
        }
    }

    override suspend fun togglePlayPause() {
        prepare()

        withContext(Dispatchers.Main) {
            requireNotNull(service).pause()
        }
    }

    override suspend fun skipNext() {
        prepare()

        withContext(Dispatchers.Main) {
            requireNotNull(service).skipNext()
        }
    }

    override suspend fun skipPrevious() {
        prepare()

        withContext(Dispatchers.Main) {
            requireNotNull(service).skipPrev()
        }
    }

    override suspend fun seekTo(positionMs: Long) {
        prepare()

        withContext(Dispatchers.Main) {
            requireNotNull(service).seekTo(positionMs)
        }
    }

    override suspend fun setShuffleEnabled(enabled: Boolean) {
        prepare()

        shuffleEnabled = enabled

        withContext(Dispatchers.Main) {
            requireNotNull(service)
                .player
                .shuffleModeEnabled = enabled
        }
    }

    override suspend fun setRepeatMode(mode: RepeatMode) {
        prepare()

        repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }

        withContext(Dispatchers.Main) {
            requireNotNull(service)
                .player
                .repeatMode = repeatMode
        }
    }

    private data class PlaybackSnapshot(
        val items: List<MediaItem>,
    )

    private companion object {
        const val TAG = "AndroidPlayerEngine"
        const val SERVICE_CHECK_DELAY_MS = 100L
    }
}
