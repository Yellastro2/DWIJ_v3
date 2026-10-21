package com.yellastrodev.dwij.playback

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.C
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

/** Связывает общий PlayerEngine с сервисом, recovery-политикой и восстановлением очереди без сброса звука. */
class AndroidPlayerEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val mediaItemMapper: AndroidMediaItemMapper,
    private val serviceRegistry:
        AndroidPlayerServiceRegistry,
) : PlayerEngine {

    private val applicationContext = context.applicationContext

    @Volatile private var failureHandler: (Int, Boolean) -> Boolean = { _, _ -> false }

    /** Подключает решение общей очереди до автоматического перехода сервиса. */
    override fun setFailureHandler(handler: (Int, Boolean) -> Boolean) {
        failureHandler = handler
        service?.onPlaybackFailure = handler
    }

    /** Останавливает намерение играть; пустая офлайн-очередь удаляет недоступные сетевые элементы. */
    override suspend fun stopPlayback(clearQueue: Boolean) {
        withContext(Dispatchers.Main) {
            service?.stopPlayback(clearQueue)
            if (clearQueue) playbackSnapshot = null
        }
    }

    /** Сохраняет текущий MediaItem и позицию, меняя только элементы до/после него. */
    override suspend fun replaceQueueKeepingCurrent(tracks: List<PlaybackTrack>, oldIndex: Int, newIndex: Int,
                                                  tracklist: dTracklist?, stillCurrent: () -> Boolean): Boolean = withContext(Dispatchers.Main) {
        val active = service ?: return@withContext false
        val player = active.player
        if (!stillCurrent() || player.currentMediaItemIndex != oldIndex || newIndex !in tracks.indices) return@withContext false
        val current = player.currentMediaItem ?: return@withContext false
        val items = tracks.map { mediaItemMapper.map(it, tracklist) }.toMutableList()
        items[newIndex] = current
        player.removeMediaItems(oldIndex + 1, player.mediaItemCount)
        player.removeMediaItems(0, oldIndex)
        player.addMediaItems(0, items.take(newIndex))
        player.addMediaItems(items.drop(newIndex + 1))
        playbackSnapshot = PlaybackSnapshot(items)
        true
    }

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
    private var queueSubscriptionJob: Job? = null

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

    /** Подписывается на сервис; снимок из памяти применяет только к ещё пустому плееру. */
    private fun restoreSnapshotIfNeeded(
        playerService: PlayerService,
    ) {
        val currentState = mutableState.value
        if (service !== playerService) {
            stopSubscriptions()
            service = playerService
            startSubscriptionsIfNeeded(playerService)
        }
        val snapshot = playbackSnapshot ?: return
        if (playerService.player.mediaItemCount > 0) return

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

    /** Подписывается на состояние и фактическую очередь, включая внешнее Media3-восстановление. */
    private fun startSubscriptionsIfNeeded(
        playerService: PlayerService,
    ) {
        if (subscriptionsStarted) {
            return
        }

        subscriptionsStarted = true
        playerService.onPlaybackFailure = failureHandler

        queueSubscriptionJob = playerService.queue
            .onEach { items ->
                if (items.isNotEmpty()) playbackSnapshot = PlaybackSnapshot(items)
            }
            .launchIn(scope)

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

    /** Отменяет все подписки на прежний экземпляр сервиса. */
    private fun stopSubscriptions() {
        queueSubscriptionJob?.cancel()
        queueSubscriptionJob = null
        stateSubscriptionJob?.cancel()
        stateSubscriptionJob = null

        eventsSubscriptionJob?.cancel()
        eventsSubscriptionJob = null

        subscriptionsStarted = false
    }

    /** Устанавливает очередь и сохраняет паузу, если смена режима не должна запускать звук. */
    override suspend fun setQueue(
        tracks: List<PlaybackTrack>,
        startIndex: Int,
        tracklist: dTracklist?,
        autoPlay: Boolean,
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
                autoPlay = autoPlay,
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

    override suspend fun getUpcomingIndices(
        limit: Int,
    ): List<Int> {
        if (limit <= 0) {
            return emptyList()
        }

        prepare()

        return withContext(Dispatchers.Main) {
            val currentPlayer = requireNotNull(service).player
            val timeline = currentPlayer.currentTimeline
            val currentIndex = currentPlayer.currentMediaItemIndex

            if (
                timeline.isEmpty ||
                currentIndex == C.INDEX_UNSET
            ) {
                return@withContext emptyList()
            }

            val result = mutableListOf<Int>()
            val visited = mutableSetOf(currentIndex)
            var index = currentIndex

            while (result.size < limit) {
                val nextIndex = timeline.getNextWindowIndex(
                    index,
                    currentPlayer.repeatMode,
                    currentPlayer.shuffleModeEnabled,
                )

                if (
                    nextIndex == C.INDEX_UNSET ||
                    !visited.add(nextIndex)
                ) {
                    break
                }

                result += nextIndex
                index = nextIndex
            }

            result
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
