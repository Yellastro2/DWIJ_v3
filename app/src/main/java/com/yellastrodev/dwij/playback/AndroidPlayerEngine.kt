package com.yellastrodev.dwij.playback

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.yellastrodev.dwij.data.entities.PlaybackTrack
import com.yellastrodev.dwij.data.entities.dTracklist
import com.yellastrodev.dwij.service.PlayerService
import com.yellastrodev.dwij.utils.PlayerEvent
import com.yellastrodev.dwij.utils.PlayerState
import com.yellastrodev.dwij.yApplication
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

@OptIn(UnstableApi::class)
class AndroidPlayerEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val mediaItemMapper: AndroidMediaItemMapper,
) : PlayerEngine {

    private val applicationContext = context.applicationContext

    private var service: PlayerService? = null

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

    private fun startPlayerService() {
        val intent = Intent(
            applicationContext,
            PlayerService::class.java,
        )

        applicationContext.startService(intent)
    }

    @OptIn(UnstableApi::class)
    private suspend fun waitForService(): PlayerService {
        while (true) {
            getApplicationService()?.let { playerService ->
                return playerService
            }

            delay(SERVICE_CHECK_DELAY_MS)
        }
    }

    private fun getApplicationService(): PlayerService? {
        return (applicationContext as yApplication)
            .playerServiceRef
            ?.get()
    }

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

        withContext(Dispatchers.Main) {
            requireNotNull(service)
                .player
                .shuffleModeEnabled = enabled
        }
    }

    override suspend fun setRepeatMode(mode: RepeatMode) {
        prepare()

        withContext(Dispatchers.Main) {
            requireNotNull(service)
                .player
                .repeatMode = when (mode) {
                RepeatMode.OFF ->
                    androidx.media3.common.Player.REPEAT_MODE_OFF

                RepeatMode.ALL ->
                    androidx.media3.common.Player.REPEAT_MODE_ALL
            }
        }
    }

    private companion object {
        const val SERVICE_CHECK_DELAY_MS = 100L
    }
}