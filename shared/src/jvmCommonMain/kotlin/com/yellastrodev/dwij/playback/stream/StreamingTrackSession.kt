package com.yellastrodev.dwij.playback.stream

import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.yamusicsdk.download.AudioRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Одна загрузка на трек: приоритет чтения, затем заполнение пробелов.
 * Чтение блокирует только поток загрузчика плеера. Ни один незаписанный байт
 * разреженного файла не выдаётся читателю. close удаляет только временный файл.
 */
class StreamingTrackSession internal constructor(
    private val trackId: String,
    private val temporary: File,
    private val source: StreamingAudioSource,
    scope: CoroutineScope,
    private val publish: (File) -> File,
    private val trimCache: suspend () -> Unit,
    private val onFailure: (DataError) -> Unit,
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private val lock = Object()
    private val ranges = DownloadedRanges()
    private var file: RandomAccessFile? = RandomAccessFile(temporary, "rw")
    private var total = -1L
    private var entityTag: String? = null
    private var priority = 0L
    private var closed = false
    private val closeRequested = AtomicBoolean(false)
    private val cleanupComplete = CompletableDeferred<Unit>()
    private var failure: IOException? = null
    private var complete = false
    private val worker: Job = scope.launch(start = CoroutineStart.LAZY) { download() }

    internal fun start() { worker.start() }

    /** Ожидает только заголовки и задаёт приоритет позиции нового открытия/seek. */
    fun length(position: Long, prioritize: Boolean = true): Long = synchronized(lock) {
        require(position >= 0)
        if (prioritize) priority = position
        while (total < 0) {
            checkReadable()
            awaitData()
        }
        checkReadable()
        total
    }

    /** Возвращает -1 только на настоящем конце файла; дырки ожидают сеть. */
    fun read(
        position: Long, buffer: ByteArray, offset: Int, length: Int, prioritize: Boolean = true,
    ): Int = synchronized(lock) {
        require(position >= 0 && offset >= 0 && length >= 0 && offset <= buffer.size - length)
        if (length == 0) return@synchronized 0
        if (prioritize) priority = position
        while (true) {
            checkReadable()
            if (total >= 0 && position >= total) return@synchronized -1
            val available = ranges.available(position)
            if (available > 0) {
                val count = minOf(length.toLong(), available).toInt()
                val input = checkNotNull(file)
                input.seek(position)
                input.readFully(buffer, offset, count)
                if (prioritize) priority = position + count
                return@synchronized count
            }
            awaitData()
        }
        @Suppress("UNREACHABLE_CODE")
        -1
    }

    internal fun isFailed(): Boolean = synchronized(lock) { failure != null || closeRequested.get() }

    internal suspend fun awaitClosed() { cleanupComplete.await() }

    /** Сохранение офлайн ждёт проверки последнего HTTP-ответа, а не только всех записей. */
    internal fun awaitComplete() = synchronized(lock) {
        while (!complete) {
            checkReadable()
            awaitData()
        }
        checkReadable()
    }

    private suspend fun download() {
        try {
            val url = when (val result = source.resolveUrl(trackId)) {
                is DataResult.Success -> result.value
                is DataResult.Failure -> throw StreamFailure(result.error)
            }
            while (true) {
                val request = synchronized(lock) {
                    checkReadable()
                    if (total > 0 && ranges.available(0) == total) {
                        // Последний HTTP-ответ уже успешно проверен, а все пробелы заполнены.
                        checkNotNull(file).fd.sync()
                        file?.close()
                        file = null
                        val target = publish(temporary)
                        file = RandomAccessFile(target, "r")
                        complete = true
                        lock.notifyAll()
                        null
                    } else {
                        val preferred = ranges.nextMissing(priority)
                        val start = if (total < 0) 0L
                            else if (preferred < total) preferred else ranges.nextMissing(0)
                        val maximum = if (total < 0) CHUNK_SIZE else minOf(CHUNK_SIZE, total - start)
                        start to ranges.missingLength(start, maximum)
                    }
                } ?: break

                log("[range] track=$trackId, offset=${request.first}, length=${request.second}")
                var attempts = 0
                while (true) {
                    when (val result = source.readRange(url, request.first, request.second, ::headers, ::write)) {
                        is DataResult.Success -> break
                        is DataResult.Failure -> {
                            // Повторяем тот же диапазон: неполный HTTP-ответ не подтверждает завершение.
                            if (attempts++ >= 1 || !result.error.isTransient()) throw StreamFailure(result.error)
                            log("[retry] track=$trackId, offset=${request.first}: повтор диапазона после сетевой ошибки")
                            delay(250)
                        }
                    }
                }
            }
            trimCache()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val dataError = (error as? StreamFailure)?.error ?: DataError.Storage(error)
            synchronized(lock) {
                if (!closed && !complete) {
                    failure = IOException("Не удалось загрузить аудио ЯМ")
                    runCatching { file?.close() }
                    file = null
                    temporary.delete()
                    lock.notifyAll()
                }
            }
            if (!synchronized(lock) { closed }) onFailure(dataError)
        } finally {
            synchronized(lock) {
                if (!complete) {
                    runCatching { file?.close() }
                    file = null
                    temporary.delete()
                    if (failure == null) failure = IOException("Загрузка отменена")
                    lock.notifyAll()
                }
            }
        }
    }

    private fun headers(range: AudioRange) = synchronized(lock) {
        checkReadable()
        require(total < 0 || total == range.totalLength) { "Размер аудио изменился" }
        require(total < 0 || entityTag == range.entityTag) { "Версия аудио изменилась" }
        total = range.totalLength
        entityTag = range.entityTag
        log("[headers] track=$trackId, offset=${range.offset}, length=${range.length}, total=${range.totalLength}")
        lock.notifyAll()
    }

    private fun write(position: Long, buffer: ByteArray, count: Int) = synchronized(lock) {
        checkReadable()
        require(position >= 0 && count > 0 && position <= total - count)
        checkNotNull(file).apply {
            seek(position)
            write(buffer, 0, count)
        }
        ranges.add(position, position + count)
        lock.notifyAll()
    }

    private fun checkReadable() {
        if (closeRequested.get()) throw IOException("Сессия трека закрыта")
        failure?.let { throw it }
    }

    private fun awaitData() {
        try {
            lock.wait()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Чтение аудио отменено")
        }
    }

    override fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        worker.cancel()
        // Вызывается также из Player.Listener на main: дисковая очистка выполняется отдельно.
        cleanupExecutor.execute {
            try {
                synchronized(lock) {
                    closed = true
                    runCatching { file?.close() }
                    file = null
                    val removed = !temporary.exists() || temporary.delete()
                    log("[close] track=$trackId, complete=$complete, временный файл удалён=$removed")
                    lock.notifyAll()
                }
            } finally {
                cleanupComplete.complete(Unit)
            }
        }
    }

    private class StreamFailure(val error: DataError) : IOException()
    private fun DataError.isTransient(): Boolean =
        this == DataError.Timeout || this == DataError.NoInternet || this is DataError.Network

    private companion object {
        const val CHUNK_SIZE = 256L * 1024
        val cleanupExecutor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "dwij-stream-cleanup").apply { isDaemon = true }
        }
    }
}
