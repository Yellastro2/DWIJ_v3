package com.yellastrodev.dwij.data.entities

import com.yellastrodev.vkmusicsdk.VkPlaylist

/** Конечная очередь VK-плейлиста; новый запуск получает собственный relay и уникальную identity. */
class VkPlaylistTracklist(private val playlist: VkPlaylist) : dTracklist {
    private val identity = "vk-playlist:${playlist.fullId}:${java.util.UUID.randomUUID()}"
    /** Идентификатор очереди отличает новый relay от предыдущего запуска этого же списка. */
    override fun getdId(): String = identity
    /** Показывает настоящее название плейлиста над очередью плеера. */
    override fun getDTitle(): String = playlist.title
    /** Отдельный тип исключает продолжение VK-плейлиста Яндекс-волной. */
    override fun getType(): String = "vk-playlist"
    /** VK-плейлист не связан с Яндекс Rotor. */
    override fun getWaveId(): String = ""
}
