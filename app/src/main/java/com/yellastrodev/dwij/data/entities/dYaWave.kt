package com.yellastrodev.dwij.data.entities

import com.yellastrodev.yandexmusiclib.entities.TrackShort
import com.yellastrodev.yandexmusiclib.entities.YaWave

class dYaWave(
    val radioSessionId: String,
    var batchId: String,
    var tracks: List<TrackShort> = listOf()
): dTracklist {

    companion object{
        const val YA_WAVE = "ya_wave"
    }


    override fun getDTitle(): String = "волна"

    override fun getType(): String = YA_WAVE
}

fun YaWave.toEntity(): dYaWave {
    return dYaWave(
        radioSessionId = radioSessionId,
        batchId = batchId,
        tracks = tracks
    )
}