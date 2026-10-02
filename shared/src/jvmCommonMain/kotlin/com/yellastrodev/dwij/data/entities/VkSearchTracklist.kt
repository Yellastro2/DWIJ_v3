package com.yellastrodev.dwij.data.entities

/** Конечная одиночная VK-очередь; завершение не должно запускать Яндекс-волну. */
class VkSearchTracklist : dTracklist {
    /** Новый id заставляет плеер обновить истёкший URL даже при повторном выборе трека. */
    override fun getdId(): String = identity
    /** Название временной очереди для плеера. */
    override fun getDTitle(): String = "ВК Музыка — поиск"
    /** Отдельный тип исключает продолжение через Яндекс Rotor. */
    override fun getType(): String = "vk-search"
    /** VK-поиск не связан с Яндекс-станцией. */
    override fun getWaveId(): String = ""
    private val identity = "vk-search:${java.util.UUID.randomUUID()}"
}
