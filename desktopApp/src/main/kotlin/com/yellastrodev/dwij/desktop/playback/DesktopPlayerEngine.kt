package com.yellastrodev.dwij.desktop.playback

import com.yellastrodev.dwij.data.entities.PlaybackTrack
import com.yellastrodev.dwij.data.entities.MusicSource
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.entities.dTracklist
import com.yellastrodev.dwij.playback.PlaybackStateStore
import com.yellastrodev.dwij.playback.PlayerEngine
import com.yellastrodev.dwij.playback.PlayerVolumeControl
import com.yellastrodev.dwij.playback.RepeatMode
import com.yellastrodev.dwij.playback.feedback.PlaybackFeedbackMetadata
import com.yellastrodev.dwij.playback.feedback.PlaybackFeedbackTracker
import com.yellastrodev.dwij.playback.feedback.PlaybackTransitionReason
import com.yellastrodev.dwij.utils.PlayerEvent
import com.yellastrodev.dwij.utils.PlayerState
import com.yellastrodev.dwij.utils.TrackChangeDirection
import com.yellastrodev.yamusicsdk.YamLogger
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

/**
 * JVM/Windows backend общего [PlayerEngine].
 *
 * JavaFX MediaPlayer отвечает за реальное воспроизведение.
 * WindowsMediaSession публикует состояние в Windows SMTC
 * и принимает системные media commands. JavaFX callback'и также передают
 * фактические события прослушивания в общий [PlaybackFeedbackTracker].
 * Подготовка ограничена 12с (VK: 45с для API/master/media/key); после трёх повторов трек пропускается.
 * Диагностика отделяет загрузку, буферизацию и декодирование без URI и текста приватных ошибок.
 * FX-поток только управляет MediaPlayer и снимает его свойства; логи, SMTC,
 * публикация прогресса и feedback выполняются последовательной фоновой очередью.
 */
class DesktopPlayerEngine(
    private val scope: CoroutineScope,
    private val logger: YamLogger,
    initialVolume: Float = DEFAULT_VOLUME,
    private val resolveUri:
    suspend (String) -> String,
    private val resolveArtworkFile:
    suspend (PlaybackTrack) -> File? = {
        null
    },
    private val closeSource: () -> Unit = {},
    private val resetSource: () -> Unit = {},
) : PlayerEngine,
    PlayerVolumeControl {

    private val stateStore =
        PlaybackStateStore()

    private val callbackQueue = DesktopPlaybackCallbackQueue(logger)

    override val state:
            StateFlow<PlayerState> =
        stateStore.state

    override val events:
            SharedFlow<PlayerEvent> =
        stateStore.events

    private val volumeState =
        MutableStateFlow(
            initialVolume.coerceIn(
                MIN_VOLUME,
                MAX_VOLUME,
            ),
        )

    override val volume:
            StateFlow<Float> =
        volumeState

    private var lastAudibleVolume =
        volumeState.value
            .takeIf {
                it > MIN_VOLUME
            }
            ?: DEFAULT_VOLUME

    private val commandMutex =
        Mutex()

    private var queue:
            List<PlaybackTrack> =
        emptyList()

    private var currentIndex =
        -1

    private var currentPlayer:
            MediaPlayer? =
        null

    @Volatile private var currentAttempt: DesktopPlaybackAttempt? = null
    private var preparationJob: Job? = null
    private var stalledJob: Job? = null
    @Volatile private var closed = false

    private var shuffleEnabled =
        false

    private val shuffleOrder =
        DesktopShuffleOrder()

    private var repeatMode =
        RepeatMode.OFF

    @Volatile
    private var playbackFeedbackTracker:
            PlaybackFeedbackTracker? =
        null

    @Volatile
    private var currentFeedbackMetadata:
            PlaybackFeedbackMetadata? =
        null

    private var currentFeedbackPlaylistId:
            String? =
        null

    @Volatile
    private var currentTrackInstanceId:
            String? =
        null

    @Volatile
    private var lastWindowsPositionUpdateNanos =
        0L

    private val windowsMediaSession =
        WindowsMediaSession(
            scope =
                scope,
            logger =
                logger,
            onPlayRequest = {
                if (!state.value.wantsToPlay) {
                    togglePlayPause()
                }
            },
            onPauseRequest = {
                if (state.value.wantsToPlay) {
                    togglePlayPause()
                }
            },
            onNextRequest = {
                skipNext()
            },
            onPreviousRequest = {
                skipPrevious()
            },
            onSeekRequest = { positionMs ->
                seekTo(
                    positionMs,
                )
            },
        )

    override suspend fun prepare() {
        JavaFxRuntime.ensureStarted()
        windowsMediaSession.prepare()
    }

    /**
     * Подключает созданный shared-компонентом tracker после сборки
     * циклически связанных DesktopPlayerEngine и DwijComponent.
     */
    fun bindPlaybackFeedback(
        tracker: PlaybackFeedbackTracker,
    ) {
        check(
            playbackFeedbackTracker == null,
        ) {
            "PlaybackFeedbackTracker уже подключён"
        }

        playbackFeedbackTracker =
            tracker
    }

    override suspend fun setQueue(
        tracks: List<PlaybackTrack>,
        startIndex: Int,
        tracklist: dTracklist?,
    ) {
        commandMutex.withLock {
            if (
                tracks.isEmpty() ||
                startIndex !in tracks.indices
            ) {
                return
            }

            queue =
                tracks.toList()

            if (shuffleEnabled) {
                shuffleOrder.reset(
                    queueSize =
                        queue.size,
                    currentIndex =
                        startIndex,
                )
            }

            currentFeedbackPlaylistId =
                (tracklist as? dYaPlaylist)
                    ?.playlistUuid

            startTrackLocked(
                index =
                    startIndex,
                autoPlay =
                    true,
                direction =
                    TrackChangeDirection.DIRECT,
                feedbackReason =
                    PlaybackTransitionReason.PLAYLIST_CHANGED,
            )
        }
    }

    override suspend fun appendTracks(
        tracks: List<PlaybackTrack>,
        tracklist: dTracklist?,
    ) {
        commandMutex.withLock {
            if (tracks.isNotEmpty()) {
                val previousQueueSize =
                    queue.size

                queue =
                    queue + tracks

                if (shuffleEnabled) {
                    shuffleOrder.append(
                        previousQueueSize =
                            previousQueueSize,
                        newQueueSize =
                            queue.size,
                    )
                }
            }
        }
    }

    override suspend fun getUpcomingIndices(
        limit: Int,
    ): List<Int> = commandMutex.withLock {
        if (
            limit <= 0 ||
            currentIndex !in queue.indices
        ) {
            return@withLock emptyList()
        }

        if (shuffleEnabled) {
            return@withLock shuffleOrder.peekNext(limit)
        }

        ((currentIndex + 1)..queue.lastIndex)
            .take(limit)
    }

    override suspend fun playTrack(
        index: Int,
    ) {
        commandMutex.withLock {
            if (index !in queue.indices) {
                return
            }

            if (shuffleEnabled) {
                shuffleOrder.select(
                    index,
                )
            }

            startTrackLocked(
                index =
                    index,
                autoPlay =
                    true,
                direction =
                    TrackChangeDirection.DIRECT,
                feedbackReason =
                    PlaybackTransitionReason.OTHER,
            )
        }
    }

    override suspend fun togglePlayPause() {
        val wantsToPlay =
            !state.value.wantsToPlay

        stateStore.setWantsToPlay(
            wantsToPlay,
        )

        JavaFxRuntime.call {
            val player =
                currentPlayer
                    ?: return@call

            if (wantsToPlay) {
                player.play()
            } else {
                player.pause()
            }
        }
    }

    override suspend fun skipNext() {
        commandMutex.withLock {
            val nextIndex =
                nextIndex()
                    ?: return

            startTrackLocked(
                index =
                    nextIndex,
                autoPlay =
                    true,
                direction =
                    TrackChangeDirection.NEXT,
                feedbackReason =
                    PlaybackTransitionReason.OTHER,
            )
        }
    }

    override suspend fun skipPrevious() {
        commandMutex.withLock {
            val previousIndex =
                previousIndex()
                    ?: return

            startTrackLocked(
                index =
                    previousIndex,
                autoPlay =
                    true,
                direction =
                    TrackChangeDirection.PREVIOUS,
                feedbackReason =
                    PlaybackTransitionReason.OTHER,
            )
        }
    }

    override suspend fun seekTo(
        positionMs: Long,
    ) {
        logger.debug(TAG, "[seekTo] instanceId=$currentTrackInstanceId, fromMs=${state.value.currentPosition}, toMs=$positionMs")
        val safePosition =
            positionMs.coerceAtLeast(
                0L,
            )

        val didSeek =
            JavaFxRuntime.call {
                val player =
                    currentPlayer
                        ?: return@call false

                player.seek(
                    Duration.millis(
                        safePosition.toDouble(),
                    ),
                )

                true
            }

        if (didSeek) {
            windowsMediaSession.setPosition(
                safePosition,
            )

            lastWindowsPositionUpdateNanos =
                System.nanoTime()
        }
    }

    override suspend fun setShuffleEnabled(
        enabled: Boolean,
    ) {
        commandMutex.withLock {
            if (shuffleEnabled == enabled) {
                return@withLock
            }

            shuffleEnabled =
                enabled

            if (enabled) {
                shuffleOrder.reset(
                    queueSize =
                        queue.size,
                    currentIndex =
                        currentIndex,
                )
            } else {
                shuffleOrder.clear()
            }

            stateStore.setShuffle(
                enabled,
            )
        }
    }

    override suspend fun setRepeatMode(
        mode: RepeatMode,
    ) {
        repeatMode =
            mode

        stateStore.setRepeatAll(
            mode == RepeatMode.ALL,
        )
    }

    override fun setVolume(
        volume: Float,
    ) {
        val resolvedVolume =
            volume.coerceIn(
                MIN_VOLUME,
                MAX_VOLUME,
            )

        if (
            resolvedVolume >
            MIN_VOLUME
        ) {
            lastAudibleVolume =
                resolvedVolume
        }

        volumeState.value =
            resolvedVolume

        JavaFxRuntime.execute {
            currentPlayer?.volume =
                resolvedVolume.toDouble()
        }
    }

    override fun toggleMute() {
        val targetVolume =
            if (
                volumeState.value >
                MIN_VOLUME
            ) {
                MIN_VOLUME
            } else {
                lastAudibleVolume
            }

        setVolume(
            targetVolume,
        )
    }

    /** Останавливает JavaFX, дожидается фоновых событий и затем закрывает feedback, SMTC и очередь. */
    fun close() {
        if (closed) return
        closed = true
        currentAttempt = null
        preparationJob?.cancel()
        stalledJob?.cancel()
        currentTrackInstanceId =
            null

        try {
            runBlocking {
                try {
                    JavaFxRuntime.call { disposeCurrentPlayer() }
                } finally {
                    callbackQueue.call {
                        try {
                            finishCurrentFeedback(state.value.currentPosition, state.value.duration, false)
                        } finally {
                            windowsMediaSession.close()
                        }
                    }
                }
            }
        } finally {
            try { closeSource() } finally { callbackQueue.close() }
        }
    }

    /** Инициализирует состояние/feedback/SMTC вне FX до создания плеера; ограничивает бюджет подготовки. */
    private suspend fun startTrackLocked(
        index: Int,
        autoPlay: Boolean,
        direction: TrackChangeDirection? = null,
        feedbackReason: PlaybackTransitionReason,
        retry: Int = 0,
    ) {
        if (closed) return
        val track =
            queue.getOrNull(
                index,
            )
                ?: return

        if (direction != null) {
            stateStore.beginTrackChange(
                direction = direction,
                wantsToPlay = autoPlay,
            )
        }

        // Старый JavaFX-плеер больше не должен читать отзываемый loopback-адрес.
        preparationJob?.cancel()
        stalledJob?.cancel()
        val attempt = DesktopPlaybackAttempt(index, retry)
        currentAttempt = attempt
        JavaFxRuntime.call { disposeCurrentPlayer() }
        resetSource()
        currentIndex = index
        val prepareTimeout = if (track.source == MusicSource.VK) DesktopPlaybackAttempt.VK_PREPARE_TIMEOUT_MS
            else DesktopPlaybackAttempt.PREPARE_TIMEOUT_MS
        preparationJob = scope.launch {
            delay(prepareTimeout)
            JavaFxRuntime.call {
                if (currentAttempt === attempt) currentPlayer?.let { logPlayerDiagnostics("prepareTimeout", it, index) }
            }
            recoverAttempt(attempt, "подготовка превысила ${prepareTimeout / 1000}с")
        }
        val prepareStarted = System.nanoTime()
        logger.debug(TAG, "[startTrack] Подготовка instanceId=${track.instanceId}")
        val resolvedUri =
            try {
                resolveUri(
                    track.playbackUri,
                )
            } catch (error: CancellationException) {
                preparationJob?.cancel()
                currentAttempt = null
                resetSource()
                throw error
            } catch (error: Exception) {
                resetSource()
                stateStore.setPlaying(false)
                windowsMediaSession.setPlaying(false)
                logger.error(
                    TAG,
                    "[startTrack] Не удалось получить playback URI " +
                            "instanceId=${track.instanceId}, type=${error.javaClass.simpleName}",
                )

                recoverAttempt(attempt, "не удалось получить адрес аудио")

                return
            }

        if (closed || currentAttempt !== attempt || attempt.isFailed) return

        currentTrackInstanceId =
            track.instanceId

        val newPlayer = try {
            callbackQueue.call {
                if (closed || currentAttempt !== attempt || attempt.isFailed) return@call
                stateStore.setPlayback(isPlaying = false, currentIndex = index)
                stateStore.setProgress(positionMs = 0L, durationMs = track.durationMs ?: 0L)
                currentFeedbackMetadata = track.toFeedbackMetadata()
                playbackFeedbackTracker?.onMediaItemTransition(
                    metadata = currentFeedbackMetadata,
                    reason = feedbackReason,
                    isPlaying = false,
                    currentPositionMs = 0L,
                    durationMs = track.durationMs,
                )
                windowsMediaSession.setTrack(track)
                lastWindowsPositionUpdateNanos = 0L
            }
            if (closed || currentAttempt !== attempt || attempt.isFailed) return

            JavaFxRuntime.call {
                if (closed || currentAttempt !== attempt || attempt.isFailed) return@call null
                val media =
                    Media(
                        resolvedUri,
                    )

                val player =
                    MediaPlayer(
                        media,
                    )

                player.volume =
                    volumeState.value
                        .toDouble()

                currentPlayer =
                    player

                installCallbacks(
                    player =
                        player,
                    index =
                        index,
                    attempt = attempt,
                )

                player
            }
        } catch (error: CancellationException) {
            preparationJob?.cancel()
            currentAttempt = null
            resetSource()
            throw error
        } catch (error: Exception) {
            JavaFxRuntime.call { disposeCurrentPlayer() }
            resetSource()
            logger.error(TAG, "[startTrack] JavaFX не открыл аудио: instanceId=${track.instanceId}, type=${error.javaClass.simpleName}")
            stateStore.setPlaying(false)
            windowsMediaSession.setPlaying(false)
            recoverAttempt(attempt, "JavaFX не открыл аудио")
            return
        } ?: return
        val prepareMs = (System.nanoTime() - prepareStarted) / 1_000_000
        logger.debug(TAG, "[startTrack] JavaFX создан: instanceId=${track.instanceId}, ${prepareMs}мс; ожидаем READY")

        requestWindowsArtwork(
            track,
        )

        if (state.value.wantsToPlay) {
            JavaFxRuntime.call {
                if (
                    currentPlayer ===
                    newPlayer && !closed && currentAttempt === attempt && !attempt.isFailed
                ) {
                    newPlayer.play()
                }
            }
        }
    }

    private fun requestWindowsArtwork(
        track: PlaybackTrack,
    ) {
        scope.launch {
            val artworkFile =
                try {
                    resolveArtworkFile(
                        track,
                    )
                } catch (
                    error: CancellationException,
                ) {
                    throw error
                } catch (error: Exception) {
                    logger.error(
                        TAG,
                        "[artwork] Не удалось получить обложку " +
                                "instanceId=${track.instanceId}",
                        error,
                    )

                    null
                }
                    ?: return@launch

            /*
             * Пока обложка грузилась/читалась из кэша,
             * пользователь уже мог переключить трек.
             */
            if (
                currentTrackInstanceId !=
                track.instanceId
            ) {
                return@launch
            }

            windowsMediaSession.setArtwork(
                trackInstanceId =
                    track.instanceId,
                file =
                    artworkFile,
            )
        }
    }

    /** Снимает свойства на FX и передаёт побочные действия в очередь; старые попытки отбрасываются. */
    private fun installCallbacks(
        player: MediaPlayer,
        index: Int,
        attempt: DesktopPlaybackAttempt,
    ) {
        val stallTimeout = if (queue.getOrNull(index)?.source == MusicSource.VK) DesktopPlaybackAttempt.VK_STALL_TIMEOUT_MS
            else DesktopPlaybackAttempt.STALL_TIMEOUT_MS
        player.statusProperty().addListener { _, previous, current ->
            if (currentPlayer === player && currentAttempt === attempt) {
                callbackQueue.post {
                    logger.debug(TAG, "[playerStatus] index=$index, статус=$previous → $current")
                }
                logPlayerDiagnostics("playerStatus", player, index)
            }
        }
        var lastBufferBucket = -1L
        player.bufferProgressTimeProperty().addListener { _, _, buffered ->
            val bucket = buffered.toMillisSafe() / 10_000
            if (currentPlayer === player && currentAttempt === attempt && bucket != lastBufferBucket) {
                lastBufferBucket = bucket
                logPlayerDiagnostics("playerBuffer", player, index)
            }
        }
        player.media.setOnError {
            if (currentPlayer === player && currentAttempt === attempt) {
                val error = player.media.error
                callbackQueue.post {
                    logger.error(TAG, "[mediaError] Ошибка медиа: index=$index, ${safeMediaError(error)}")
                }
                logPlayerDiagnostics("mediaError", player, index)
            }
        }
        player.setOnHalted {
            if (currentPlayer === player && currentAttempt === attempt) logPlayerDiagnostics("playerHalted", player, index)
        }
        player.setOnReady {
            if (
                currentPlayer !== player ||
                currentIndex != index || currentAttempt !== attempt || attempt.isFailed
            ) {
                return@setOnReady
            }
            logPlayerDiagnostics("ready", player, index)
            preparationJob?.cancel()

            val positionMs =
                player.currentTime
                    .toMillisSafe()

            val durationMs =
                player.totalDuration
                    .toMillisSafe()

            dispatchCallback(attempt) {
                if (attempt.isFailed) return@dispatchCallback
                logger.debug(TAG, "[ready] Плеер готов: index=$index, длительностьМс=$durationMs")
                stateStore.setProgress(positionMs, durationMs)
                stateStore.completeTrackChange()
                windowsMediaSession.setTimeline(durationMs, positionMs)
                notifyFeedbackProgress(positionMs, durationMs)
                lastWindowsPositionUpdateNanos = System.nanoTime()
            }
        }

        player.setOnPlaying {
            if (
                currentPlayer === player && currentAttempt === attempt && !attempt.isFailed
            ) {
                stalledJob?.cancel()
                logPlayerDiagnostics("playing", player, index)
                val positionMs = player.currentTime.toMillisSafe()
                val durationMs = player.totalDuration.toMillisSafe()
                dispatchCallback(attempt) {
                    if (attempt.isFailed) return@dispatchCallback
                    logger.debug(TAG, "[playing] Воспроизведение началось: index=$index, позицияМс=$positionMs")
                    stateStore.setPlaying(true)
                    windowsMediaSession.setPlaying(true)
                    notifyFeedbackPlaying(true, positionMs, durationMs)
                }
            }
        }

        player.setOnStalled {
            if (currentPlayer === player && currentAttempt === attempt && !attempt.isFailed) {
                val positionMs = player.currentTime.toMillisSafe()
                callbackQueue.post {
                    logger.warning(TAG, "[stalled] index=$index, позицияМс=$positionMs: JavaFX ожидает данные")
                }
                logPlayerDiagnostics("stalled", player, index)
                if (stalledJob?.isActive != true && state.value.wantsToPlay) {
                    stalledJob = scope.launch {
                        delay(stallTimeout)
                        if (state.value.wantsToPlay) recoverAttempt(attempt, "JavaFX ожидает данные более ${stallTimeout / 1000}с")
                    }
                }
            }
        }

        player.setOnPaused {
            if (
                currentPlayer === player && currentAttempt === attempt
            ) {
                stalledJob?.cancel()
                val positionMs = player.currentTime.toMillisSafe()
                val durationMs = player.totalDuration.toMillisSafe()
                dispatchCallback(attempt) {
                    stateStore.setPlaying(false)
                    windowsMediaSession.setPlaying(false)
                    notifyFeedbackPlaying(false, positionMs, durationMs)
                }
            }
        }

        player.setOnStopped {
            if (
                currentPlayer === player && currentAttempt === attempt
            ) {
                val positionMs = player.currentTime.toMillisSafe()
                val durationMs = player.totalDuration.toMillisSafe()
                dispatchCallback(attempt) {
                    stateStore.setPlaying(false)
                    windowsMediaSession.setPlaying(false)
                    notifyFeedbackPlaying(false, positionMs, durationMs)
                }
            }
        }

        var lastProgressUpdateNanos = 0L
        player.currentTimeProperty()
            .addListener {
                    _,
                    _,
                    currentTime,
                ->

                if (
                    currentPlayer === player && currentAttempt === attempt && !attempt.isFailed
                ) {
                    val now = System.nanoTime()
                    if (lastProgressUpdateNanos != 0L && now - lastProgressUpdateNanos < PROGRESS_UPDATE_INTERVAL_NANOS) {
                        return@addListener
                    }
                    lastProgressUpdateNanos = now
                    val positionMs =
                        currentTime
                            .toMillisSafe()

                    val durationMs =
                        player.totalDuration
                            .toMillisSafe()

                    callbackQueue.postProgress {
                        if (!closed && currentAttempt === attempt && !attempt.isFailed) {
                            stateStore.setProgress(positionMs, durationMs)
                            maybeUpdateWindowsPosition(positionMs)
                            notifyFeedbackProgress(positionMs, durationMs)
                        }
                    }
                }
            }

        player.setOnEndOfMedia {
            if (
                currentPlayer !== player || currentAttempt !== attempt || attempt.isFailed
            ) {
                return@setOnEndOfMedia
            }

            val positionMs = player.currentTime.toMillisSafe()
            val durationMs = player.totalDuration.toMillisSafe()
            dispatchCallback(attempt) {
                if (attempt.isFailed) return@dispatchCallback
                stateStore.setProgress(positionMs, durationMs)
                finishCurrentFeedback(positionMs, durationMs, true)
                scope.launch { handleTrackEnded(finishedIndex = index, attempt = attempt) }
            }
        }

        player.setOnError {
            if (
                currentPlayer !== player || currentAttempt !== attempt || attempt.isFailed
            ) {
                return@setOnError
            }

            val error =
                player.error

            logPlayerDiagnostics("playerError", player, index)
            val positionMs = player.currentTime.toMillisSafe()
            val durationMs = player.totalDuration.toMillisSafe()
            dispatchCallback(attempt) {
                logger.error(TAG, "[MediaPlayer] Ошибка воспроизведения: index=$index, ${safeMediaError(error)}")
                windowsMediaSession.setPlaying(false)
                finishCurrentFeedback(positionMs, durationMs, false)
            }

            recoverAttempt(attempt, "ошибка JavaFX ${error?.type}")
        }
    }

    /** Исполняет побочные действия в последовательной очереди, пока их попытка остаётся текущей. */
    private fun dispatchCallback(attempt: DesktopPlaybackAttempt, action: () -> Unit) {
        callbackQueue.post {
            if (!closed && currentAttempt === attempt) action()
        }
    }

    /** Снимает свойства на FX; форматирование, обход причин ошибок и запись происходят в фоне. */
    private fun logPlayerDiagnostics(stage: String, player: MediaPlayer, index: Int) {
        val source = queue.getOrNull(index)?.source
        val status = player.status
        val positionMs = player.currentTime.toMillisSafe()
        val bufferMs = player.bufferProgressTime.toMillisSafe()
        val durationMs = player.totalDuration.toMillisSafe()
        val wantsToPlay = state.value.wantsToPlay
        val mute = player.isMute
        val volume = player.volume
        val rate = player.rate
        val trackTypes = player.media.tracks.map { it.javaClass }
        val playerError = player.error
        val mediaError = player.media.error
        callbackQueue.post {
            logger.debug(TAG, "[$stage] index=$index, источник=$source, " +
                "статус=$status, позицияМс=$positionMs, буферМс=$bufferMs, длительностьМс=$durationMs, " +
                "хочетИграть=$wantsToPlay, mute=$mute, громкость=$volume, скорость=$rate, " +
                "дорожки=${trackTypes.joinToString { it.simpleName }}, " +
                "ошибкаПлеера=${safeMediaError(playerError)}, ошибкаМедиа=${safeMediaError(mediaError)}")
        }
    }

    /** Описывает тип ошибки и классы причин; message/stack trace могут содержать приватную медиассылку. */
    private fun safeMediaError(error: javafx.scene.media.MediaException?): String {
        if (error == null) return "нет"
        val causes = generateSequence(error as Throwable) { it.cause }.take(4).joinToString(" → ") { it.javaClass.simpleName }
        return "тип=${error.type}, причины=$causes"
    }

    /** Принимает отказ без IO на FX; лог и восстановление выполняются фоновыми обработчиками. */
    private fun recoverAttempt(attempt: DesktopPlaybackAttempt, reason: String) {
        if (closed || currentAttempt !== attempt || !attempt.fail()) return
        callbackQueue.post {
            logger.warning(TAG, "[retryTrack] index=${attempt.index}, попытка=${attempt.retry + 1}/4: $reason")
        }
        scope.launch {
            commandMutex.withLock {
                if (closed || currentAttempt !== attempt) return@withLock
                preparationJob?.cancel()
                stalledJob?.cancel()
                JavaFxRuntime.call { disposeCurrentPlayer() }
                resetSource()
                callbackQueue.call {
                    stateStore.setPlaying(false)
                    windowsMediaSession.setPlaying(false)
                }
            }
            delay(DesktopPlaybackAttempt.RETRY_DELAY_MS)
            commandMutex.withLock {
                if (closed || currentAttempt !== attempt) return@withLock
                val autoPlay = state.value.wantsToPlay
                if (attempt.canRetry) {
                    startTrackLocked(
                        index = attempt.index,
                        autoPlay = autoPlay,
                        feedbackReason = PlaybackTransitionReason.OTHER,
                        retry = attempt.retry + 1,
                    )
                } else {
                    callbackQueue.call {
                        finishCurrentFeedback(state.value.currentPosition, state.value.duration, false)
                    }
                    val next = nextIndex()?.takeIf { it != attempt.index }
                    logger.warning(TAG, "[skipFailedTrack] index=${attempt.index}: четыре попытки исчерпаны, следующий=$next")
                    if (next != null) {
                        startTrackLocked(
                            index = next,
                            autoPlay = autoPlay,
                            direction = TrackChangeDirection.NEXT,
                            feedbackReason = PlaybackTransitionReason.AUTO,
                        )
                    } else {
                        currentAttempt = null
                        stateStore.setWantsToPlay(false)
                        stateStore.completeTrackChange()
                        stateStore.emit(PlayerEvent.TrackListEnd("Playlist finished"))
                    }
                }
            }
        }
    }

    /** Ограничивает SMTC-обновления; вызывается только фоновым обработчиком событий. */
    private fun maybeUpdateWindowsPosition(
        positionMs: Long,
    ) {
        val now =
            System.nanoTime()

        val previous =
            lastWindowsPositionUpdateNanos

        if (
            previous != 0L &&
            now - previous <
            WINDOWS_POSITION_UPDATE_INTERVAL_NANOS
        ) {
            return
        }

        lastWindowsPositionUpdateNanos =
            now

        windowsMediaSession.setPosition(
            positionMs,
        )
    }

    /** Продолжает очередь только для всё ещё актуальной, успешно завершённой попытки. */
    private suspend fun handleTrackEnded(
        finishedIndex: Int,
        attempt: DesktopPlaybackAttempt,
    ) {
        commandMutex.withLock {
            if (
                currentIndex !=
                finishedIndex || currentAttempt !== attempt || attempt.isFailed || closed
            ) {
                return
            }

            val nextIndex =
                nextIndex()

            if (
                nextIndex == null
            ) {
                stateStore.setPlaying(
                    false,
                )
                stateStore.setWantsToPlay(
                    false,
                )

                windowsMediaSession.setPlaying(
                    false,
                )

                stateStore.emit(
                    PlayerEvent.TrackListEnd(
                        "Playlist finished",
                    ),
                )

                return
            }

            startTrackLocked(
                index =
                    nextIndex,
                autoPlay =
                    true,
                feedbackReason =
                    PlaybackTransitionReason.AUTO,
            )
        }
    }

    private fun PlaybackTrack.toFeedbackMetadata():
            PlaybackFeedbackMetadata =
        PlaybackFeedbackMetadata(
            trackId =
                id,
            itemId =
                UUID.randomUUID()
                    .toString(),
            albumId =
                yandexTrack
                    ?.albums
                    ?.firstOrNull()
                    ?.id
                    ?.toString(),
            playlistId =
                currentFeedbackPlaylistId,
            reportSource =
                DESKTOP_PLAYBACK_REPORT_SOURCE,
            durationMs =
                durationMs,
            musicSource =
                source,
            isYandexAvailable =
                yandexTrack
                    ?.available,
        )

    /** Передаёт feedback снимок позиции без обращения к MediaPlayer из фонового потока. */
    private fun notifyFeedbackPlaying(isPlaying: Boolean, positionMs: Long, durationMs: Long) {
        playbackFeedbackTracker
            ?.onIsPlayingChanged(
                metadata =
                    currentFeedbackMetadata,
                isPlaying =
                    isPlaying,
                currentPositionMs =
                    positionMs,
                durationMs =
                    durationMs
                        .takeIf {
                            it > 0L
                        },
            )
    }

    private fun notifyFeedbackProgress(
        positionMs: Long,
        durationMs: Long,
    ) {
        playbackFeedbackTracker
            ?.onProgress(
                trackId =
                    currentFeedbackMetadata
                        ?.trackId,
                currentPositionMs =
                    positionMs,
                durationMs =
                    durationMs
                        .takeIf {
                            it > 0L
                        },
            )
    }

    private fun finishCurrentFeedback(
        positionMs: Long,
        durationMs: Long,
        completed: Boolean,
    ) {
        if (currentFeedbackMetadata == null) {
            return
        }

        playbackFeedbackTracker
            ?.onPlaybackEnded(
                currentPositionMs =
                    positionMs,
                durationMs =
                    durationMs
                        .takeIf {
                            it > 0L
                        },
                completed =
                    completed,
            )

        currentFeedbackMetadata =
            null
    }

    private fun nextIndex():
            Int? {

        if (
            queue.isEmpty() ||
            currentIndex !in queue.indices
        ) {
            return null
        }

        if (
            shuffleEnabled
        ) {
            return shuffleOrder.next(
                repeatAll =
                    repeatMode ==
                    RepeatMode.ALL,
            )
        }

        if (
            currentIndex <
            queue.lastIndex
        ) {
            return currentIndex + 1
        }

        return if (
            repeatMode ==
            RepeatMode.ALL
        ) {
            0
        } else {
            null
        }
    }

    private fun previousIndex():
            Int? {

        if (
            queue.isEmpty() ||
            currentIndex !in queue.indices
        ) {
            return null
        }

        if (
            shuffleEnabled
        ) {
            return shuffleOrder.previous()
        }

        if (
            currentIndex > 0
        ) {
            return currentIndex - 1
        }

        return if (
            repeatMode ==
            RepeatMode.ALL
        ) {
            queue.lastIndex
        } else {
            0
        }
    }

    private fun disposeCurrentPlayer() {
        currentPlayer
            ?.runCatching {
                stop()
                dispose()
            }

        currentPlayer =
            null
    }

    private fun Duration?.toMillisSafe():
            Long {

        if (
            this == null ||
            isUnknown ||
            isIndefinite
        ) {
            return 0L
        }

        return toMillis()
            .takeIf(
                Double::isFinite,
            )
            ?.coerceAtLeast(
                0.0,
            )
            ?.toLong()
            ?: 0L
    }

    private companion object {

        const val TAG =
            "DesktopPlayerEngine"

        const val DESKTOP_PLAYBACK_REPORT_SOURCE =
            "dwij-desktop"

        const val MIN_VOLUME =
            0f

        const val MAX_VOLUME =
            1f

        const val DEFAULT_VOLUME =
            1f

        const val WINDOWS_POSITION_UPDATE_INTERVAL_NANOS =
            2_000_000_000L

        const val PROGRESS_UPDATE_INTERVAL_NANOS =
            200_000_000L
    }
}
