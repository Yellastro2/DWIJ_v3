package com.yellastrodev.dwij.desktop.playback

import com.yellastrodev.yamusicsdk.YamLogger
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

/**
 * Последовательно обрабатывает редкие события плеера вне JavaFX Application Thread.
 * Частый прогресс объединяется в одно последнее значение, а [call] создаёт барьер
 * перед сменой трека и закрытием. В задания нельзя передавать обращения к MediaPlayer.
 */
internal class DesktopPlaybackCallbackQueue(private val logger: YamLogger) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "dwij-playback-events").apply { isDaemon = true }
    }
    private val progress = AtomicReference<(() -> Unit)?>(null)

    /** Ставит редкое событие в очередь без ожидания выполнения на вызывающем потоке. */
    fun post(action: () -> Unit): Boolean = try {
        executor.execute {
            try {
                action()
            } catch (error: Exception) {
                logger.error("DesktopPlaybackCallbackQueue", "[post] Ошибка обработки события плеера", error)
            }
        }
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    /** Заменяет ожидающий прогресс; одновременно ожидает не более одного задания прогресса. */
    fun postProgress(action: () -> Unit) {
        if (executor.isShutdown) return
        if (progress.getAndSet(action) == null) {
            if (!post { progress.getAndSet(null)?.invoke() }) {
                progress.set(null)
            }
        }
    }

    /** Ждёт результат задания, пропуская перед ним уже принятые события; FX-поток не блокируется. */
    suspend fun <T> call(action: () -> T): T {
        val result = CompletableDeferred<T>()
        check(post {
            try {
                result.complete(action())
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }) { "Обработчик событий плеера закрыт" }
        return result.await()
    }

    /** Запрещает новые задания; принятые задания завершаются перед остановкой daemon-потока. */
    override fun close() {
        executor.shutdown()
    }
}
