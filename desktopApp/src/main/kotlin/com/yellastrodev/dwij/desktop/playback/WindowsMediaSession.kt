package com.yellastrodev.dwij.desktop.playback

import com.yellastrodev.dwij.data.entities.PlaybackTrack
import com.yellastrodev.yamusicsdk.YamLogger
import io.github.selemba1000.JMTC
import io.github.selemba1000.JMTCButtonCallback
import io.github.selemba1000.JMTCCallbacks
import io.github.selemba1000.JMTCEnabledButtons
import io.github.selemba1000.JMTCMediaType
import io.github.selemba1000.JMTCMusicProperties
import io.github.selemba1000.JMTCPlayingState
import io.github.selemba1000.JMTCSeekCallback
import io.github.selemba1000.JMTCSettings
import io.github.selemba1000.JMTCTimelineProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.sun.jna.Native
import com.sun.jna.WString
import io.github.selemba1000.windows.SMTCAdapter
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Windows System Media Transport Controls adapter.
 *
 * Изолирует стороннюю JMTC-библиотеку от остального desktop-кода.
 *
 * DWIJ -> Windows:
 * - текущий трек;
 * - исполнитель;
 * - play/pause;
 * - duration;
 * - position.
 *
 * Windows -> DWIJ:
 * - Play;
 * - Pause;
 * - Next;
 * - Previous;
 * - Seek.
 */
class WindowsMediaSession(
    private val scope: CoroutineScope,
    private val logger: YamLogger,
    private val onPlayRequest: suspend () -> Unit,
    private val onPauseRequest: suspend () -> Unit,
    private val onNextRequest: suspend () -> Unit,
    private val onPreviousRequest: suspend () -> Unit,
    private val onSeekRequest: suspend (Long) -> Unit,
) {

    private val initializationLock =
        Any()

    @Volatile
    private var initializationAttempted =
        false

    @Volatile
    private var closed =
        false

    @Volatile
    private var control:
            JMTC? =
        null

    @Volatile
    private var nativeAdapter:
            SMTCAdapter? =
        null

    @Volatile
    private var currentTrackInstanceId:
            String? =
        null

    @Volatile
    private var timelineDurationMs =
        0L

    /**
     * Лениво регистрирует DWIJ в Windows media subsystem.
     *
     * Вызывается перед первым реальным воспроизведением.
     * Ошибка SMTC не должна ломать сам музыкальный плеер.
     */
    fun prepare() {
        if (
            initializationAttempted ||
            closed
        ) {
            return
        }

        synchronized(
            initializationLock,
        ) {
            if (
                initializationAttempted ||
                closed
            ) {
                return
            }

            initializationAttempted =
                true

            control =
                createControl()
        }
    }

    /**
     * Публикует новый текущий трек в Windows.
     */
    fun setTrack(
        track: PlaybackTrack,
    ) {
        currentTrackInstanceId =
            track.instanceId

        val durationMs =
            track.durationMs
                ?.coerceAtLeast(
                    0L,
                )
                ?: 0L

        timelineDurationMs =
            durationMs

        withControl(
            actionName =
                "setTrack",
        ) { control ->
            /*
             * Между старым и новым треком сообщаем Windows,
             * что media item меняется.
             */
            control.setPlayingState(
                JMTCPlayingState.CHANGING,
            )

            /*
             * Убираем thumbnail предыдущего трека.
             * Новая обложка догрузится отдельно.
             */
            control.resetDisplay()

            control.setMediaType(
                JMTCMediaType.Music,
            )

            control.setMediaProperties(
                JMTCMusicProperties(
                    track.title,
                    track.artistNames
                        .joinToString(
                            separator = ", ",
                        ),
                    "",
                    "",
                    emptyArray<String>(),
                    -1,
                    -1,
                    null,
                ),
            )

            if (
                durationMs > 0L
            ) {
                control.setTimelineProperties(
                    JMTCTimelineProperties(
                        0L,
                        durationMs,
                        0L,
                        durationMs,
                    ),
                )
            }

            control.setPosition(
                0L,
            )

            /*
             * Реальный PLAYING придёт из callback JavaFX MediaPlayer.
             */
            control.setPlayingState(
                JMTCPlayingState.PAUSED,
            )

            control.updateDisplay()
        }
    }

    /**
     * Синхронизирует реальное состояние JavaFX MediaPlayer с Windows.
     */
    fun setPlaying(
        isPlaying: Boolean,
    ) {
        withControl(
            actionName =
                "setPlaying",
        ) { control ->
            control.setPlayingState(
                if (isPlaying) {
                    JMTCPlayingState.PLAYING
                } else {
                    JMTCPlayingState.PAUSED
                },
            )
        }
    }

    /**
     * Обновляет длительность и seekable range.
     *
     * Обычно вызывается после MediaPlayer.onReady,
     * когда JavaFX уже знает фактическую duration.
     */
    fun setTimeline(
        durationMs: Long,
        positionMs: Long,
    ) {
        val safeDuration =
            durationMs.coerceAtLeast(
                0L,
            )

        timelineDurationMs =
            safeDuration

        if (
            safeDuration <= 0L
        ) {
            return
        }

        val safePosition =
            positionMs.coerceIn(
                0L,
                safeDuration,
            )

        withControl(
            actionName =
                "setTimeline",
        ) { control ->
            control.setTimelineProperties(
                JMTCTimelineProperties(
                    0L,
                    safeDuration,
                    0L,
                    safeDuration,
                ),
            )

            control.setPosition(
                safePosition,
            )
        }
    }

    suspend fun setArtwork(
        trackInstanceId: String,
        file: File,
    ) {
        if (
            closed ||
            currentTrackInstanceId !=
            trackInstanceId ||
            !file.isFile
        ) {
            return
        }

        val adapter =
            nativeAdapter
                ?: return

        try {
            /*
             * ВАЖНО:
             * JMTC wrapper здесь не используем.
             *
             * Его Java-код передаёт file:/C:/...
             * а Windows StorageFile ожидает C:\...
             */
            adapter.setThumbnail(
                WString(
                    file.absolutePath,
                ),
            )

            val loaded =
                withTimeoutOrNull(
                    THUMBNAIL_LOAD_TIMEOUT_MS,
                ) {
                    while (
                        adapter.thumbnailLoaded() !=
                        true
                    ) {
                        delay(
                            THUMBNAIL_POLL_INTERVAL_MS,
                        )
                    }

                    true
                } == true

            if (!loaded) {
                logger.warning(
                    TAG,
                    "[artwork] Windows не загрузила thumbnail: " +
                            file.absolutePath,
                )

                return
            }

            /*
             * Пока картинка грузилась,
             * пользователь мог уже переключить трек.
             */
            if (
                closed ||
                currentTrackInstanceId !=
                trackInstanceId
            ) {
                return
            }

            control
                ?.updateDisplay()

            logger.debug(
                TAG,
                "[artwork] Thumbnail установлен: " +
                        file.name,
            )
        } catch (
            error: CancellationException,
        ) {
            throw error
        } catch (error: Throwable) {
            logger.error(
                TAG,
                "[artwork] Не удалось установить thumbnail",
                error,
            )
        }
    }

    /**
     * Обновляет текущую позицию Windows timeline.
     *
     * DesktopPlayerEngine сам ограничивает частоту вызова.
     */
    fun setPosition(
        positionMs: Long,
    ) {
        val safePosition =
            if (
                timelineDurationMs > 0L
            ) {
                positionMs.coerceIn(
                    0L,
                    timelineDurationMs,
                )
            } else {
                positionMs.coerceAtLeast(
                    0L,
                )
            }

        withControl(
            actionName =
                "setPosition",
        ) { control ->
            control.setPosition(
                safePosition,
            )
        }
    }

    /**
     * Отключает DWIJ от Windows media UI.
     */
    fun close() {
        if (closed) {
            return
        }

        currentTrackInstanceId =
            null

        nativeAdapter =
            null

        closed =
            true

        val currentControl =
            control

        control =
            null

        if (
            currentControl == null
        ) {
            return
        }

        try {
            currentControl.resetDisplay()

            currentControl.setPlayingState(
                JMTCPlayingState.CLOSED,
            )

            currentControl.setEnabled(
                false,
            )

            logger.info(
                TAG,
                "[close] Windows media session закрыта",
            )
        } catch (error: Throwable) {
            logger.error(
                TAG,
                "[close] Не удалось закрыть Windows media session",
                error,
            )
        }
    }

    /**
     * Создаёт нативную Windows SMTC session и подписывает callbacks.
     *
     * Здесь намеренно ловится Throwable, а не только Exception:
     * JNA/native loader при отсутствии DLL может выбросить LinkageError.
     */
    private fun createControl():
            JMTC? =
        try {
            val instance =
                requireNotNull(
                    JMTC.getInstance(
                        JMTCSettings(
                            PLAYER_NAME,
                            PLAYER_DESKTOP_FILE,
                        ),
                    ),
                ) {
                    "JMTC не поддерживает текущую платформу"
                }

            nativeAdapter =
                Native.load(
                    "SMTCAdapter",
                    SMTCAdapter::class.java,
                )

            val callbacks =
                JMTCCallbacks().apply {
                    onPlay =
                        JMTCButtonCallback {
                            dispatch(
                                actionName =
                                    "play",
                                action =
                                    onPlayRequest,
                            )
                        }

                    onPause =
                        JMTCButtonCallback {
                            dispatch(
                                actionName =
                                    "pause",
                                action =
                                    onPauseRequest,
                            )
                        }

                    onNext =
                        JMTCButtonCallback {
                            dispatch(
                                actionName =
                                    "next",
                                action =
                                    onNextRequest,
                            )
                        }

                    onPrevious =
                        JMTCButtonCallback {
                            dispatch(
                                actionName =
                                    "previous",
                                action =
                                    onPreviousRequest,
                            )
                        }

                    onSeek =
                        JMTCSeekCallback { positionMs ->
                            dispatch(
                                actionName =
                                    "seek",
                            ) {
                                onSeekRequest(
                                    positionMs
                                        .coerceAtLeast(
                                            0L,
                                        ),
                                )
                            }
                        }
                }

            instance.setCallbacks(
                callbacks,
            )

            /*
             * Stop пока не нужен.
             *
             * Play/Pause/Next/Previous становятся доступны
             * в Windows media transport controls.
             */
            instance.setEnabledButtons(
                JMTCEnabledButtons(
                    true,
                    true,
                    false,
                    true,
                    true,
                ),
            )

            instance.setMediaType(
                JMTCMediaType.Music,
            )

            instance.setPlayingState(
                JMTCPlayingState.CLOSED,
            )

            instance.setEnabled(
                true,
            )

            logger.info(
                TAG,
                "[prepare] Windows media session создана",
            )

            instance
        } catch (error: Throwable) {
            logger.error(
                TAG,
                "[prepare] Windows media session недоступна",
                error,
            )

            null
        }

    /**
     * Native callbacks могут приходить не из coroutine context приложения.
     *
     * Поэтому переводим их в application scope.
     */
    private fun dispatch(
        actionName: String,
        action: suspend () -> Unit,
    ) {
        if (closed) {
            return
        }

        scope.launch {
            try {
                action()
            } catch (
                error: CancellationException,
            ) {
                throw error
            } catch (error: Exception) {
                logger.error(
                    TAG,
                    "[callback:$actionName] Ошибка обработки Windows media command",
                    error,
                )
            }
        }
    }

    /**
     * Все native вызовы проходят через одну безопасную точку.
     *
     * Если SMTC сломался, воспроизведение JavaFX продолжает работать.
     */
    private inline fun withControl(
        actionName: String,
        block: (JMTC) -> Unit,
    ) {
        if (closed) {
            return
        }

        prepare()

        val currentControl =
            control
                ?: return

        try {
            block(
                currentControl,
            )
        } catch (error: Throwable) {
            logger.error(
                TAG,
                "[$actionName] Ошибка Windows media session",
                error,
            )
        }
    }

    private companion object {

        const val THUMBNAIL_LOAD_TIMEOUT_MS =
            3_000L

        const val THUMBNAIL_POLL_INTERVAL_MS =
            10L

        const val TAG =
            "WindowsMediaSession"

        const val PLAYER_NAME =
            "DWIJ"

        /*
         * На Windows эти два значения JMTC фактически не использует:
         * они нужны Linux/MPRIS реализации библиотеки.
         */
        const val PLAYER_DESKTOP_FILE =
            "DWIJ"
    }
}