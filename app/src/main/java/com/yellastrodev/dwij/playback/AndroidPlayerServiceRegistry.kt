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

    fun attach(service: PlayerService) {
        serviceReference =
            WeakReference(service)
    }

    fun detach(service: PlayerService) {
        if (serviceReference?.get() === service) {
            serviceReference = null
        }
    }

    fun current(): PlayerService? =
        serviceReference?.get()
}
