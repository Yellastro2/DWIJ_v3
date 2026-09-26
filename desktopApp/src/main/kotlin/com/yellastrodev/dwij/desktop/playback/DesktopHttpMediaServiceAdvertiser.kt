package com.yellastrodev.dwij.desktop.playback

import com.yellastrodev.dwij.playback.HttpMediaServiceAdvertiser
import com.yellastrodev.yamusicsdk.YamLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/** Объявляет desktop HTTP-пульт через JmDNS; сетевые операции выполняются вне UI-потока. */
class DesktopHttpMediaServiceAdvertiser(private val logger: YamLogger) : HttpMediaServiceAdvertiser {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "dwij-http-mdns").apply { isDaemon = true }
    }
    private val generation = AtomicLong()
    private var mdns: JmDNS? = null
    private val mutableServiceName = MutableStateFlow<String?>(null)
    override val serviceName = mutableServiceName.asStateFlow()

    /** Регистрирует HTTP-сервис на выбранном IPv4 и публикует фактическое имя после проверки конфликтов. */
    override fun start(port: Int, address: String?) {
        val request = generation.incrementAndGet()
        mutableServiceName.value = null
        worker.execute {
            if (generation.get() != request) return@execute
            release()
            if (address == null) {
                logger.warning(TAG, "[start] Нет локального IPv4 для объявления HTTP-пульта")
                return@execute
            }
            var created: JmDNS? = null
            try {
                val service = ServiceInfo.create(
                    "${HttpMediaServiceAdvertiser.SERVICE_TYPE}local.",
                    HttpMediaServiceAdvertiser.SERVICE_NAME,
                    port, 0, 0, mapOf("app" to "dwij"),
                )
                val instance = JmDNS.create(InetAddress.getByName(address))
                created = instance
                instance.registerService(service)
                if (generation.get() == request) {
                    mdns = instance
                    mutableServiceName.value = service.name
                    created = null
                }
            } catch (failure: Exception) {
                logger.error(TAG, "[start] Не удалось объявить HTTP-пульт через mDNS", failure)
            } finally {
                created?.let(::closeInstance)
            }
        }
    }

    /** Снимает объявление и отменяет результат незавершённой регистрации. */
    override fun stop() {
        val request = generation.incrementAndGet()
        mutableServiceName.value = null
        worker.execute {
            if (generation.get() == request) {
                release()
                mutableServiceName.value = null
            }
        }
    }

    /** Закрывает текущий экземпляр JmDNS с отправкой goodbye-пакетов. */
    private fun release() {
        val current = mdns ?: return
        mdns = null
        closeInstance(current)
    }

    /** Освобождает сокеты и потоки библиотеки, сохраняя HTTP-сервер при ошибке объявления. */
    private fun closeInstance(instance: JmDNS) {
        try {
            instance.close()
        } catch (failure: Exception) {
            logger.error(TAG, "[closeInstance] Не удалось закрыть объявление HTTP-пульта", failure)
        }
    }

    private companion object { const val TAG = "HttpMediaMdns" }
}
