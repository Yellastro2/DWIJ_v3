package com.yellastrodev.dwij.playback

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import com.yellastrodev.yamusicsdk.YamLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** Объявляет HTTP-пульт через системный NSD; Android автоматически разрешает конфликты имён. */
class AndroidHttpMediaServiceAdvertiser(context: Context, private val logger: YamLogger) : HttpMediaServiceAdvertiser {
    private val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val handler = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()
    private var listener: NsdManager.RegistrationListener? = null
    private val mutableServiceName = MutableStateFlow<String?>(null)
    override val serviceName = mutableServiceName.asStateFlow()

    /** Ставит регистрацию в очередь главного потока; адрес выбирает системный NSD. */
    override fun start(port: Int, address: String?) {
        val request = generation.incrementAndGet()
        mutableServiceName.value = null
        handler.post {
            if (generation.get() != request) return@post
            unregisterCurrent()
            register(port, request)
        }
    }

    /** Отменяет ожидающую регистрацию и снимает текущую. */
    override fun stop() {
        val request = generation.incrementAndGet()
        mutableServiceName.value = null
        handler.post {
            if (generation.get() == request) {
                unregisterCurrent()
                mutableServiceName.value = null
            }
        }
    }

    /** Создаёт отдельный listener для каждого порта и игнорирует устаревшие callbacks. */
    private fun register(port: Int, request: Long) {
        val registration = object : NsdManager.RegistrationListener {
            /** Показывает имя, фактически назначенное системой после проверки конфликтов. */
            override fun onServiceRegistered(info: NsdServiceInfo) {
                handler.post {
                    if (generation.get() == request && listener === this) {
                        mutableServiceName.value = info.serviceName
                    } else {
                        unregister(this)
                    }
                }
            }

            /** Ошибка объявления не отключает работающий HTTP-сервер. */
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                handler.post {
                    if (listener === this) listener = null
                    logger.warning(TAG, "[onRegistrationFailed] Не удалось объявить HTTP-пульт: код $errorCode")
                }
            }

            /** Очищает имя только для текущей регистрации. */
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                handler.post {
                    if (listener === this) {
                        listener = null
                        mutableServiceName.value = null
                    }
                }
            }

            /** Записывает ошибку снятия объявления для диагностики NSD. */
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                logger.warning(TAG, "[onUnregistrationFailed] Не удалось снять объявление HTTP-пульта: код $errorCode")
            }
        }
        listener = registration
        try {
            val info = NsdServiceInfo().apply {
                serviceName = HttpMediaServiceAdvertiser.SERVICE_NAME
                serviceType = HttpMediaServiceAdvertiser.SERVICE_TYPE
                setPort(port)
                setAttribute("app", "dwij")
            }
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
        } catch (failure: Exception) {
            listener = null
            logger.error(TAG, "[register] Не удалось зарегистрировать HTTP-пульт", failure)
        }
    }

    /** Сбрасывает текущий listener до снятия объявления, чтобы старые callbacks не меняли новое имя. */
    private fun unregisterCurrent() {
        val registration = listener ?: return
        listener = null
        unregister(registration)
    }

    /** Снимает регистрацию, включая случай выключения во время асинхронного запуска. */
    private fun unregister(registration: NsdManager.RegistrationListener) {
        try {
            manager.unregisterService(registration)
        } catch (_: IllegalArgumentException) {
            // Listener уже снят или регистрация ещё не завершилась; поздний callback снимет её.
        } catch (failure: Exception) {
            logger.error(TAG, "[unregister] Не удалось снять объявление HTTP-пульта", failure)
        }
    }

    private companion object { const val TAG = "HttpMediaNsd" }
}
