package com.yellastrodev.dwij.data.entities

/** Контекст очереди: валидный seed ЯМ задаётся явно, происхождение не зависит от выбранного аудио. */
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


}
