package com.yellastrodev.dwij.data.entities

/** Песня и источник, из которого она попала в состав внутренней подборки. */
data class MixedTracklistEntry(val song: Song, val origin: MusicSource)

/** Конечная внутренняя подборка; одна песня представлена один раз, происхождение сохраняется при shuffle. */
class MixedTracklist(
    private val queueId: String,
    private val title: String,
    entries: List<MixedTracklistEntry>,
) : dTracklist {
    val entries: List<MixedTracklistEntry> = entries.toList()
    private val origins = this.entries.associate { it.song.id to it.origin }
    val songs: List<Song> = this.entries.map { it.song }

    init {
        require(queueId.isNotBlank())
        require(origins.size == this.entries.size) { "Смешанный треклист должен содержать уникальные песни" }
    }

    /** Пространство внутренних очередей не совпадает с серверными плейлистами. */
    override fun getdId(): String = "mixed:$queueId"
    /** Название внутренней подборки в плеере. */
    override fun getDTitle(): String = title
    /** Тип внутреннего мультисурсового списка. */
    override fun getType(): String = "mixed"
    /** Внутренняя подборка не является seed-объектом ЯМ. */
    override fun getWaveId(): String = ""
    /** Стабильная связь по песне переживает перестановку очереди и смену проигрываемого экземпляра. */
    override fun originOf(songId: String): MusicSource? = origins[songId]
    /** Перемешивание нарушило бы чередование источников. */
    override fun preserveQueueOrder(): Boolean = true
}
