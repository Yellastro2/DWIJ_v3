package com.yellastrodev.dwij.desktop.playback

import javafx.application.Platform
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Инициализирует JavaFX toolkit без JavaFX-окна и выполняет audio-команды
 * на JavaFX Application Thread.
 */
object JavaFxRuntime {

    private val startLock =
        Any()

    @Volatile
    private var started =
        false

    fun ensureStarted() {
        if (started) {
            return
        }

        synchronized(startLock) {
            if (started) {
                return
            }

            val latch =
                CountDownLatch(1)

            try {
                Platform.startup {
                    Platform.setImplicitExit(
                        false,
                    )

                    latch.countDown()
                }

                latch.await()
            } catch (
                alreadyStarted: IllegalStateException,
            ) {
                /*
                 * Toolkit уже запущен кем-то ещё.
                 */
            }

            started =
                true
        }
    }

    /**
     * Запускает короткую команду на JavaFX Application Thread
     * без ожидания результата.
     *
     * Подходит для частых UI-команд вроде изменения громкости.
     */
    fun execute(
        block: () -> Unit,
    ) {
        ensureStarted()

        if (
            Platform.isFxApplicationThread()
        ) {
            block()
            return
        }

        Platform.runLater(
            block,
        )
    }

    /**
     * Выполняет команду на JavaFX Application Thread
     * и suspend-ится до получения результата.
     */
    suspend fun <T> call(
        block: () -> T,
    ): T {
        ensureStarted()

        if (
            Platform.isFxApplicationThread()
        ) {
            return block()
        }

        return suspendCoroutine { continuation ->
            Platform.runLater {
                try {
                    continuation.resumeWith(
                        Result.success(
                            block(),
                        ),
                    )
                } catch (error: Throwable) {
                    continuation.resumeWithException(
                        error,
                    )
                }
            }
        }
    }
}