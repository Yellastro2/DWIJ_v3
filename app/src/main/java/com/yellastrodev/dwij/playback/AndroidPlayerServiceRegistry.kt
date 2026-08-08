package com.yellastrodev.dwij.playback


import com.yellastrodev.dwij.service.PlayerService
import java.lang.ref.WeakReference

/**
 * Хранит слабую ссылку на живой Android PlayerService.
 */
class AndroidPlayerServiceRegistry {

    @Volatile
    private var serviceReference:
        WeakReference<PlayerService>? = null

    @Volatile
    private var serviceAttachedListener:
        ((PlayerService) -> Unit)? = null

    fun attach(service: PlayerService) {
        serviceReference =
            WeakReference(service)

        serviceAttachedListener?.invoke(service)
    }

    fun detach(service: PlayerService) {
        if (serviceReference?.get() === service) {
            serviceReference = null
        }
    }

    fun current(): PlayerService? =
        serviceReference?.get()

    /**
     * Регистрирует application-scoped обработчик нового экземпляра сервиса.
     * PlayerService вызывает его из onCreate на главном потоке.
     */
    fun setServiceAttachedListener(
        listener: (PlayerService) -> Unit,
    ) {
        serviceAttachedListener = listener
    }
}
