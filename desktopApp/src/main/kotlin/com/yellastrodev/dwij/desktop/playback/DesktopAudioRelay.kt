package com.yellastrodev.dwij.desktop.playback

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.yellastrodev.dwij.data.repo.TrackCacheRepository
import com.yellastrodev.yamusicsdk.YamLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Источник HTTP-ответа без зависимости от JavaFX: файл либо общая сессия загрузки. */
internal interface RelayAudioReader : AutoCloseable {
    fun length(): Long
    fun read(position: Long, buffer: ByteArray, length: Int): Int
}

/**
 * Один loopback-сервер на desktop runtime. ОС атомарно выделяет порт при bind(0).
 * Случайный путь разрешает только выбранный трек; произвольные URL и файлы не обслуживаются.
 * GET/HEAD и одиночный Range поддерживают JavaFX seek без отдельной загрузки аудио.
 */
internal class DesktopAudioRelay(
    private val logger: YamLogger,
    private val readyFile: (String) -> File?,
    private val openAudio: (String) -> RelayAudioReader,
    private val selectTrack: (String?) -> Unit,
    private val closeSource: () -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val requestIds = AtomicLong()
    private val requests = ConcurrentHashMap<Long, ActiveRequest>()
    private val executor = Executors.newFixedThreadPool(8) { task ->
        Thread(task, "dwij-audio-http").apply { isDaemon = true }
    }
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "dwij-audio-http-timeouts").apply { isDaemon = true }
    }
    @Volatile private var route: Route? = null
    private var server: HttpServer? = null
    private var closed = false

    init {
        watchdog.scheduleWithFixedDelay({
            val now = System.nanoTime()
            requests.values.forEach { request ->
                if (!request.cancelled && now - request.lastProgressNs > TimeUnit.SECONDS.toNanos(30)) {
                    logger.warning(TAG, "[timeout] request=${request.id}, track=${request.route.trackId}: нет прогресса 30с")
                    request.cancel("таймаут")
                }
            }
        }, 5, 5, TimeUnit.SECONDS)
    }

    /** Вызывается после остановки прежнего JavaFX-плеера; сеть здесь не ожидается. */
    suspend fun resolve(uri: String): String = withContext(Dispatchers.IO) {
        synchronized(lock) {
            check(!closed) { "Аудиосервер закрыт" }
            invalidateRoute()
            if (!uri.startsWith("ya:", ignoreCase = true)) {
                selectTrack(null)
                return@synchronized uri
            }
            val parsed = URI(uri)
            val trackId = parsed.authority ?: parsed.schemeSpecificPart.removePrefix("//")
            require(trackId.matches(Regex("[A-Za-z0-9_-]+"))) { "Некорректный ID трека ЯМ" }
            selectTrack(trackId)
            readyFile(trackId)?.let { file ->
                logger.debug(TAG, "[resolve] track=$trackId: готовый локальный файл, bytes=${file.length()}")
                return@synchronized file.toURI().toString()
            }
            val http = server ?: startServer().also { server = it }
            val selected = Route(trackId, "/audio/${UUID.randomUUID()}.mp3")
            route = selected
            logger.info(TAG, "[resolve] track=$trackId: поток через 127.0.0.1:${http.address.port}")
            "http://127.0.0.1:${http.address.port}${selected.path}"
        }
    }

    private fun startServer(): HttpServer {
        val http = try {
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 16)
        } catch (error: Exception) {
            logger.error(TAG, "[bind] ОС не выделила loopback-порт: ${error.javaClass.simpleName}")
            throw error
        }
        try {
            http.executor = executor
            http.createContext("/", ::handle)
            http.start()
            logger.info(TAG, "[start] Аудиосервер слушает 127.0.0.1:${http.address.port}; порт выделен ОС")
            return http
        } catch (error: Exception) {
            http.stop(0)
            logger.error(TAG, "[start] Не удалось запустить аудиосервер: ${error.javaClass.simpleName}")
            throw error
        }
    }

    private fun handle(exchange: HttpExchange) {
        val id = requestIds.incrementAndGet()
        val selected = route
        if (!exchange.remoteAddress.address.isLoopbackAddress || selected == null ||
            exchange.requestURI.rawPath != selected.path || exchange.requestURI.rawQuery != null ||
            exchange.requestHeaders.getFirst("Host") != "127.0.0.1:${exchange.localAddress.port}"
        ) {
            respondEmpty(exchange, 404)
            logger.debug(TAG, "[reject] request=$id: неизвестный или устаревший адрес")
            return
        }
        val method = exchange.requestMethod
        if (method != "GET" && method != "HEAD") {
            exchange.responseHeaders.set("Allow", "GET, HEAD")
            respondEmpty(exchange, 405)
            return
        }
        val request = ActiveRequest(id, selected, exchange, Thread.currentThread())
        requests[id] = request
        var reader: RelayAudioReader? = null
        var sentHeaders = false
        var sent = 0L
        var stage = "открытие источника"
        val started = System.nanoTime()
        try {
            if (route !== selected) throw IOException("Трек сменился")
            logger.debug(TAG, "[request] request=$id, track=${selected.trackId}, method=$method, range=${exchange.requestHeaders.containsKey("Range")}")
            reader = openAudio(selected.trackId)
            val total = reader.length()
            require(total > 0) { "Пустой аудиофайл" }
            // У нас нет валидатора для If-Range: безопасно отдаём полное представление.
            val requestedRange = if (method == "HEAD" || exchange.requestHeaders.containsKey("If-Range")) null
                else exchange.requestHeaders.getFirst("Range")
            val range = try {
                parseRelayRange(requestedRange, total)
            } catch (_: IllegalArgumentException) {
                exchange.responseHeaders.set("Content-Range", "bytes */$total")
                respondEmpty(exchange, 416)
                logger.warning(TAG, "[range] request=$id, track=${selected.trackId}: диапазон отклонён, total=$total")
                return
            }
            val length = range.last - range.first + 1
            val status = if (requestedRange == null) 200 else 206
            exchange.responseHeaders.apply {
                set("Content-Type", "audio/mpeg")
                set("Accept-Ranges", "bytes")
                set("Cache-Control", "no-store")
                set("Content-Length", length.toString())
                if (status == 206) set("Content-Range", "bytes ${range.first}-${range.last}/$total")
            }
            if (route !== selected || request.cancelled) throw IOException("Запрос отменён")
            exchange.sendResponseHeaders(status, if (method == "HEAD") -1 else length)
            sentHeaders = true
            request.progress()
            logger.debug(TAG, "[headers] request=$id, track=${selected.trackId}: HTTP $status, bytes=${range.first}-${range.last}/$total, ожидание=${elapsedMs(started)}мс")
            if (method == "HEAD") return
            val buffer = ByteArray(32 * 1024)
            while (sent < length) {
                if (request.cancelled || route !== selected) throw IOException("Запрос отменён")
                stage = "чтение кэша/ожидание ЯМ"
                val readStarted = System.nanoTime()
                val count = reader.read(range.first + sent, buffer, minOf(buffer.size.toLong(), length - sent).toInt())
                if (count <= 0) throw IOException("Преждевременный конец аудио")
                val waited = elapsedMs(readStarted)
                if (waited >= 200) logger.debug(TAG, "[wait] request=$id, track=${selected.trackId}, offset=${range.first + sent}: данные получены за ${waited}мс")
                stage = "отправка JavaFX"
                exchange.responseBody.write(buffer, 0, count)
                exchange.responseBody.flush()
                if (sent == 0L) logger.debug(TAG, "[firstBytes] request=$id, track=${selected.trackId}, offset=${range.first}: ${elapsedMs(started)}мс")
                sent += count
                request.progress()
            }
            logger.debug(TAG, "[complete] request=$id, track=${selected.trackId}: отправлено=$sent байт, ${elapsedMs(started)}мс")
        } catch (error: Exception) {
            if (request.cancelled || route !== selected) {
                logger.debug(TAG, "[cancel] request=$id, track=${selected.trackId}: ${request.cancelReason}, отправлено=$sent")
            } else if (stage == "отправка JavaFX" && error is IOException) {
                logger.debug(TAG, "[disconnect] request=$id, track=${selected.trackId}: JavaFX закрыл чтение (возможен seek), отправлено=$sent")
            } else {
                logger.error(TAG, "[error] request=$id, track=${selected.trackId}, этап=$stage, отправлено=$sent, type=${error.javaClass.simpleName}")
                if (!sentHeaders) runCatching { respondEmpty(exchange, 502) }
            }
        } finally {
            request.finish()
            requests.remove(id)
            runCatching { reader?.close() }
            try { exchange.close() } finally { Thread.interrupted() }
        }
    }

    /** Отзывает старый путь и закрывает запросы, в том числе ожидающие ещё не скачанные байты. */
    private fun invalidateRoute() {
        route = null
        requests.values.forEach { it.cancel("смена трека/закрытие плеера") }
    }

    /** При ошибке подготовки не оставляет загрузку без владельца. Сервер остаётся пригодным для retry. */
    fun reset() = synchronized(lock) {
        invalidateRoute()
        selectTrack(null)
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            invalidateRoute()
            server?.stop(0)
            server = null
            closeSource()
            watchdog.shutdownNow()
            executor.shutdownNow()
            logger.info(TAG, "[close] Аудиосервер остановлен, порт освобождён")
        }
    }

    private data class Route(val trackId: String, val path: String)

    private class ActiveRequest(val id: Long, val route: Route, val exchange: HttpExchange, val thread: Thread) {
        @Volatile var lastProgressNs = System.nanoTime()
        @Volatile var cancelled = false
        @Volatile var cancelReason = "смена трека"
        private var finished = false
        fun progress() { lastProgressNs = System.nanoTime() }
        @Synchronized fun cancel(reason: String) {
            if (finished || cancelled) return
            cancelled = true
            cancelReason = reason
            thread.interrupt()
            runCatching { exchange.close() }
        }
        @Synchronized fun finish() { finished = true }
    }

    companion object {
        private const val TAG = "DesktopAudioRelay"
        private fun elapsedMs(start: Long) = (System.nanoTime() - start) / 1_000_000
        private fun respondEmpty(exchange: HttpExchange, status: Int) {
            try { exchange.sendResponseHeaders(status, -1) } finally { exchange.close() }
        }

        /** Привязывает relay к тому же кэшу, который обслуживает Android. */
        fun create(repository: TrackCacheRepository, logger: YamLogger): DesktopAudioRelay {
            val cache = repository.createStreamingCache()
            return DesktopAudioRelay(logger, repository::readyFile, { trackId ->
                val local = repository.readyFile(trackId)
                if (local != null) {
                    val file = RandomAccessFile(local, "r")
                    object : RelayAudioReader {
                        override fun length() = file.length()
                        override fun read(position: Long, buffer: ByteArray, length: Int): Int {
                            file.seek(position)
                            return file.read(buffer, 0, length)
                        }
                        override fun close() = file.close()
                    }
                } else {
                    val session = cache.acquire(trackId)
                    object : RelayAudioReader {
                        override fun length() = session.length(0, prioritize = false)
                        override fun read(position: Long, buffer: ByteArray, length: Int) =
                            session.read(position, buffer, 0, length)
                        override fun close() = cache.release(trackId, session)
                    }
                }
            }, cache::selectTrack, cache::close)
        }
    }
}

/** Одиночный HTTP byte range, включая открытый конец и suffix; multi-range отклоняется. */
internal fun parseRelayRange(header: String?, total: Long): LongRange {
    require(total > 0)
    if (header == null) return 0L until total
    val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header.trim())
        ?: throw IllegalArgumentException("Некорректный Range")
    val first = match.groupValues[1]
    val last = match.groupValues[2]
    if (first.isEmpty()) {
        val suffix = last.toLongOrNull() ?: throw IllegalArgumentException("Нет suffix")
        require(suffix > 0)
        return (total - minOf(suffix, total)) until total
    }
    val start = first.toLongOrNull() ?: throw IllegalArgumentException("Нет начала")
    val end = if (last.isEmpty()) total - 1 else last.toLongOrNull()
        ?: throw IllegalArgumentException("Нет конца")
    require(start < total && end >= start)
    return start..minOf(end, total - 1)
}
