package com.yellastrodev.dwij.data.entities

import com.yellastrodev.yamusicsdk.entities.TrackShort
import com.yellastrodev.yamusicsdk.entities.YaWave

/** Серверная станция ЯМ с контекстом продолжения и происхождением элементов очереди. */
class dYaWave(
    val radioSessionId: String,
    var batchId: String,
    var tracks: List<TrackShort> = listOf()
): dTracklist {

    var title = "волна"

    override fun getdId(): String = radioSessionId

    companion object{
        const val YA_WAVE = "ya_wave"
    }


    override fun getDTitle(): String = title

    override fun getType(): String = YA_WAVE
    /** Идентификатор станции, возвращённый rotor, применяется для продолжения очереди. */
    override fun getWaveId(): String = radioSessionId
    /** Продолжает серверную станцию по её исходному идентификатору. */
    override fun yandexWaveSeed(): String? = radioSessionId.takeIf(String::isNotBlank)
    /** Источник рекомендации остаётся ЯМ при смене проигрываемого экземпляра. */
    override fun originOf(songId: String): MusicSource = MusicSource.YANDEX
}

fun YaWave.toEntity(): dYaWave {
    return dYaWave(
        radioSessionId = radioSessionId,
        batchId = batchId,
        tracks = tracks
    )
}
