package com.yellastrodev.dwij.playback

import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.storage.LocalKeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Минимальный HTTP-пульт с платформенным DNS-SD объявлением и тремя POST-командами. */
class HttpMediaRemote(
    private val storage: LocalKeyValueStore,
    private val player: PlayerRepository,
    private val scope: CoroutineScope,
    private val advertiser: HttpMediaServiceAdvertiser,
) : AutoCloseable {
    /** Наблюдаемое имя сервиса, включая суффикс при конфликте в сети. */
    val serviceName = advertiser.serviceName
    @Volatile var error: String? = null
        private set

    @Volatile private var socket: ServerSocket? = null
    private val running = AtomicBoolean(false)

    var port: Int
        get() = (storage.getLong(KEY_PORT)?.toInt() ?: DEFAULT_PORT).takeIf { it in 1..65535 } ?: DEFAULT_PORT
        private set(value) { storage.edit { putLong(KEY_PORT, value.toLong()) } }

    var enabled: Boolean
        get() = storage.getBoolean(KEY_ENABLED) ?: false
        private set(value) { storage.edit { putBoolean(KEY_ENABLED, value) } }

    /** Возвращает первый доступный локальный IPv4-адрес; при нескольких сетях адрес может потребовать проверки. */
    fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    /** Восстанавливает сохранённое включение после запуска приложения. */
    fun startIfEnabled() {
        if (enabled) start()
    }

    /** Меняет порт после проверки и перезапускает включённый сервер. */
    fun changePort(value: Int): Boolean {
        if (value !in 1..65535) return false
        val wasEnabled = enabled
        stop()
        port = value
        if (wasEnabled) start()
        return error == null
    }

    /** Включает или выключает доступ из локальной сети. */
    fun changeEnabled(value: Boolean) {
        if (value) start() else stop()
    }

    /** Открывает TCP-порт, запускает приём и объявляет сервис в локальной сети. */
    @Synchronized private fun start() {
        if (running.get()) return
        try {
            val server = ServerSocket(port)
            socket = server
            running.set(true)
            enabled = true
            error = null
            Thread({ accept(server) }, "dwij-http-media-remote").apply { isDaemon = true }.start()
        } catch (failure: Exception) {
            error = failure.message ?: "Не удалось открыть порт $port"
            enabled = false
        }
        if (running.get()) advertiser.start(port, localAddress())
    }

    /** Останавливает приём и объявление; при закрытии процесса сохраняет настройку включения. */
    @Synchronized private fun stop(persist: Boolean = true) {
        running.set(false)
        advertiser.stop()
        socket?.close()
        socket = null
        if (persist) enabled = false
        error = null
    }

    /** Принимает запросы последовательно, без выделения потока на каждого клиента. */
    private fun accept(server: ServerSocket) {
        while (running.get() && !server.isClosed) {
            try {
                server.accept().use(::handle)
            } catch (_: SocketException) {
                break
            } catch (_: Exception) {
                // Ошибка отдельного клиента не останавливает пульт.
            }
        }
    }

    /** Разбирает только первую строку запроса и выполняет одну разрешённую команду. */
    private fun handle(client: Socket) {
        client.soTimeout = 2000
        val line = try { client.getInputStream().bufferedReader().readLine() } catch (_: SocketTimeoutException) { null }
        val command = when (line) {
            "POST /previous HTTP/1.1", "POST /previous HTTP/1.0" -> "previous"
            "POST /play-pause HTTP/1.1", "POST /play-pause HTTP/1.0" -> "play-pause"
            "POST /next HTTP/1.1", "POST /next HTTP/1.0" -> "next"
            else -> null
        }
        if (command != null) {
            scope.launch(Dispatchers.Default) {
                when (command) {
                    "previous" -> player.skipPrev()
                    "play-pause" -> player.pause()
                    "next" -> player.skipNext()
                }
            }
        }
        val status = if (command == null) "404 Not Found" else "204 No Content"
        client.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    }

    /** Освобождает сокет при завершении приложения. */
    override fun close() = stop(persist = false)

    private companion object {
        const val KEY_PORT = "http_media_remote_port"
        const val KEY_ENABLED = "http_media_remote_enabled"
        const val DEFAULT_PORT = 8765
    }
}
