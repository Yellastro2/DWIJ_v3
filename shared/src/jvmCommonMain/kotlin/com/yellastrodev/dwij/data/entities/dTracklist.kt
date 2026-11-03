package com.yellastrodev.dwij.data.entities

/** Контекст очереди с явными seed ЯМ и запретом сети; происхождение не зависит от выбранного аудио. */
interface dTracklist {

    fun getdId(): String
    fun getDTitle(): String
    fun getType(): String
    fun getWaveId(): String

    /** Возвращает поддерживаемый seed rotor; null запрещает автоматическое продолжение ЯМ-волной. */
    fun yandexWaveSeed(): String? = null

    /** Источник сущности в этом списке, независимо от экземпляра, выбранного для воспроизведения. */
    fun originOf(songId: String): MusicSource? = null

    /** Сохраняет порядок очереди, когда он является частью смысла подборки. */
    fun preserveQueueOrder(): Boolean = false

    /** Запрещает сетевые экземпляры и восстановление онлайн для явно выбранной локальной коллекции. */
    fun offlineOnly(): Boolean = false


}
