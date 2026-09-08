package com.yellastrodev.dwij.playback.stream

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runInterruptible
import java.io.OutputStream

/**
 * Координатор на время жизни плеера. Закрытие читателя при seek сохраняет текущую
 * сессию; смена трека удаляет прежнюю. Предварительно открытый следующий трек
 * сохраняется до перехода либо закрытия его последнего читателя.
 */
class StreamingTrackCache internal constructor(
    private val createSession: (String, CoroutineScope) -> StreamingTrackSession,
    private val onClose: () -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = mutableMapOf<String, Entry>()
    private var currentTrack: String? = null
    private var closed = false

    @Synchronized
    fun selectTrack(trackId: String?) {
        if (closed || currentTrack == trackId) return
        val previous = currentTrack
        currentTrack = trackId
        sessions[previous]?.takeIf { it.saves == 0 }?.let {
            sessions.remove(previous)
            it.session.close()
        }
    }

    @Synchronized
    fun acquire(trackId: String): StreamingTrackSession {
        check(!closed) { "Потоковый кэш закрыт" }
        sessions[trackId]?.takeIf { it.session.isFailed() }?.let {
            sessions.remove(trackId)
            it.session.close()
        }
        val entry = sessions.getOrPut(trackId) {
            Entry(createSession(trackId, scope).also { it.start() })
        }
        entry.readers++
        return entry.session
    }

    @Synchronized
    fun release(trackId: String, session: StreamingTrackSession) {
        val entry = sessions[trackId]?.takeIf { it.session === session } ?: return
        entry.readers--
        if (entry.readers == 0 && entry.saves == 0 && (trackId != currentTrack || session.isFailed())) {
            sessions.remove(trackId)
            session.close()
        }
    }

    /** Используется при очистке кэша; текущий ID сохраняется для повторного открытия. */
    @Synchronized
    fun clear() {
        sessions.values.forEach { it.session.close() }
        sessions.clear()
    }

    /** Остановка плеера не отменяет отдельно запрошенное сохранение офлайн. */
    @Synchronized
    fun stopPlayback() {
        val disposable = sessions.filterValues { it.saves == 0 }.keys.toList()
        disposable.forEach { sessions.remove(it)?.session?.close() }
    }

    /** Явное сохранение использует ту же загрузку и удерживает её при смене трека. */
    suspend fun copyTo(
        trackId: String,
        output: OutputStream,
        onProgress: (Long, Long?) -> Unit,
    ): Long {
        val session = synchronized(this) {
            acquire(trackId).also { sessions.getValue(trackId).saves++ }
        }
        try {
            return runInterruptible(Dispatchers.IO) {
                val total = session.length(0, prioritize = false)
                val buffer = ByteArray(64 * 1024)
                var position = 0L
                onProgress(0, total)
                while (position < total) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    val count = session.read(position, buffer, 0, buffer.size, prioritize = false)
                    check(count > 0) { "Преждевременный конец аудио" }
                    output.write(buffer, 0, count)
                    position += count
                    onProgress(position, total)
                }
                session.awaitComplete()
                output.flush()
                position
            }
        } finally {
            synchronized(this) {
                sessions[trackId]?.takeIf { it.session === session }?.let { it.saves-- }
                release(trackId, session)
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        clear()
        scope.cancel()
        onClose()
    }

    private class Entry(val session: StreamingTrackSession, var readers: Int = 0, var saves: Int = 0)
}
