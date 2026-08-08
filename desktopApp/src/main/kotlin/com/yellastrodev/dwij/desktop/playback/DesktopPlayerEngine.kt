package com.yellastrodev.dwij.desktop.playback

import com.yellastrodev.dwij.data.entities.PlaybackTrack
import com.yellastrodev.dwij.data.entities.dTracklist
import com.yellastrodev.dwij.playback.PlaybackStateStore
import com.yellastrodev.dwij.playback.PlayerEngine
import com.yellastrodev.dwij.playback.PlayerVolumeControl
import com.yellastrodev.dwij.playback.RepeatMode
import com.yellastrodev.dwij.utils.PlayerEvent
import com.yellastrodev.dwij.utils.PlayerState
import com.yellastrodev.yandexmusiclib.YamLogger
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * Первый JVM/Windows backend общего [PlayerEngine].
 *
 * Использует JavaFX Media только как платформенный декодер/вывод звука.
 * Очередь и публичное состояние остаются в тех же shared-моделях, что Android.
 *
 * Дополнительно реализует [PlayerVolumeControl], потому что desktop-версии
 * нужен собственный регулятор громкости приложения.
 */
class DesktopPlayerEngine(
    private val scope: CoroutineScope,
    private val logger: YamLogger,
    initialVolume: Float,
    private val resolveUri:
    suspend (String) -> String,
) : PlayerEngine,
    PlayerVolumeControl {

    private val stateStore =
        PlaybackStateStore()

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

    /**
     * Последняя ненулевая громкость.
     *
     * Нужна, чтобы mute -> unmute возвращал прежнее значение,
     * а не всегда 100%.
     */
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

    private var shuffleEnabled =
        false

    private var repeatMode =
        RepeatMode.OFF

    override suspend fun prepare() {
        JavaFxRuntime.ensureStarted()
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

            startTrackLocked(
                index =
                    startIndex,
                autoPlay =
                    true,
            )
        }
    }

    override suspend fun appendTracks(
        tracks: List<PlaybackTrack>,
        tracklist: dTracklist?,
    ) {
        commandMutex.withLock {
            if (
                tracks.isNotEmpty()
            ) {
                queue =
                    queue + tracks
            }
        }
    }

    override suspend fun playTrack(
        index: Int,
    ) {
        commandMutex.withLock {
            if (
                index !in queue.indices
            ) {
                return
            }

            startTrackLocked(
                index =
                    index,
                autoPlay =
                    true,
            )
        }
    }

    override suspend fun togglePlayPause() {
        JavaFxRuntime.call {
            val player =
                currentPlayer
                    ?: return@call

            if (
                player.status ==
                MediaPlayer.Status.PLAYING
            ) {
                player.pause()
            } else {
                player.play()
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
            )
        }
    }

    override suspend fun seekTo(
        positionMs: Long,
    ) {
        JavaFxRuntime.call {
            currentPlayer?.seek(
                Duration.millis(
                    positionMs
                        .coerceAtLeast(
                            0L,
                        )
                        .toDouble(),
                ),
            )
        }
    }

    override suspend fun setShuffleEnabled(
        enabled: Boolean,
    ) {
        shuffleEnabled =
            enabled

        stateStore.setShuffle(
            enabled,
        )
    }

    override suspend fun setRepeatMode(
        mode: RepeatMode,
    ) {
        repeatMode =
            mode

        stateStore.setRepeatAll(
            mode ==
                    RepeatMode.ALL,
        )
    }

    /**
     * Меняет громкость только DWIJ, не системную громкость Windows.
     */
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

    /**
     * Переключает mute с восстановлением последней ненулевой громкости.
     */
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

    /**
     * Освобождает JavaFX MediaPlayer при завершении desktop-процесса.
     */
    fun close() {
        runBlocking {
            JavaFxRuntime.call {
                disposeCurrentPlayer()
            }
        }
    }

    private suspend fun startTrackLocked(
        index: Int,
        autoPlay: Boolean,
    ) {
        val track =
            queue.getOrNull(
                index,
            )
                ?: return

        val resolvedUri =
            try {
                resolveUri(
                    track.playbackUri,
                )
            } catch (error: Exception) {
                logger.error(
                    TAG,
                    "[startTrack] Не удалось получить playback URI " +
                            "instanceId=${track.instanceId}",
                    error,
                )

                stateStore.emit(
                    PlayerEvent.ShowError(
                        "Не удалось подготовить трек",
                    ),
                )

                return
            }

        JavaFxRuntime.call {
            disposeCurrentPlayer()

            currentIndex =
                index

            stateStore.setPlayback(
                isPlaying =
                    false,
                currentIndex =
                    index,
            )

            stateStore.setProgress(
                positionMs =
                    0L,
                durationMs =
                    track.durationMs
                        ?: 0L,
            )

            val media =
                Media(
                    resolvedUri,
                )

            val player =
                MediaPlayer(
                    media,
                )

            /*
             * MediaPlayer создаётся заново для каждого трека.
             *
             * Поэтому обязательно восстанавливаем громкость
             * до начала воспроизведения.
             */
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
            )

            if (autoPlay) {
                player.play()
            }
        }
    }

    private fun installCallbacks(
        player: MediaPlayer,
        index: Int,
    ) {
        player.setOnReady {
            if (
                currentPlayer !== player ||
                currentIndex != index
            ) {
                return@setOnReady
            }

            stateStore.setProgress(
                positionMs =
                    player.currentTime
                        .toMillisSafe(),
                durationMs =
                    player.totalDuration
                        .toMillisSafe(),
            )
        }

        player.setOnPlaying {
            if (
                currentPlayer === player
            ) {
                stateStore.setPlaying(
                    true,
                )
            }
        }

        player.setOnPaused {
            if (
                currentPlayer === player
            ) {
                stateStore.setPlaying(
                    false,
                )
            }
        }

        player.setOnStopped {
            if (
                currentPlayer === player
            ) {
                stateStore.setPlaying(
                    false,
                )
            }
        }

        player.currentTimeProperty()
            .addListener {
                    _,
                    _,
                    currentTime,
                ->

                if (
                    currentPlayer === player
                ) {
                    stateStore.setProgress(
                        positionMs =
                            currentTime
                                .toMillisSafe(),
                        durationMs =
                            player.totalDuration
                                .toMillisSafe(),
                    )
                }
            }

        player.setOnEndOfMedia {
            if (
                currentPlayer !== player
            ) {
                return@setOnEndOfMedia
            }

            scope.launch {
                handleTrackEnded(
                    finishedIndex =
                        index,
                )
            }
        }

        player.setOnError {
            if (
                currentPlayer !== player
            ) {
                return@setOnError
            }

            val error =
                player.error

            logger.error(
                TAG,
                "[MediaPlayer] Ошибка воспроизведения " +
                        "index=$index",
                error,
            )

            scope.launch {
                stateStore.emit(
                    PlayerEvent.ShowError(
                        "Ошибка воспроизведения",
                    ),
                )

                skipNext()
            }
        }
    }

    private suspend fun handleTrackEnded(
        finishedIndex: Int,
    ) {
        commandMutex.withLock {
            if (
                currentIndex !=
                finishedIndex
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
            )
        }
    }

    private fun nextIndex(): Int? {
        if (
            queue.isEmpty() ||
            currentIndex !in queue.indices
        ) {
            return null
        }

        if (
            shuffleEnabled &&
            queue.size > 1
        ) {
            val candidates =
                queue.indices
                    .filter { index ->
                        index !=
                                currentIndex
                    }

            return candidates.random(
                Random.Default,
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

    private fun previousIndex(): Int? {
        if (
            queue.isEmpty() ||
            currentIndex !in queue.indices
        ) {
            return null
        }

        if (
            shuffleEnabled &&
            queue.size > 1
        ) {
            val candidates =
                queue.indices
                    .filter { index ->
                        index !=
                                currentIndex
                    }

            return candidates.random(
                Random.Default,
            )
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

    private fun Duration?.toMillisSafe(): Long {
        if (
            this == null ||
            isUnknown ||
            isIndefinite
        ) {
            return 0L
        }

        return toMillis()
            .takeIf(Double::isFinite)
            ?.coerceAtLeast(
                0.0,
            )
            ?.toLong()
            ?: 0L
    }

    private companion object {

        const val TAG =
            "DesktopPlayerEngine"

        const val MIN_VOLUME =
            0f

        const val MAX_VOLUME =
            1f

        const val DEFAULT_VOLUME =
            1f
    }
}