package com.yellastrodev.yandexmusiclib.entities

import kotlinx.serialization.Serializable

@Serializable
class YaWave(
    val radioSessionId: String,
    var batchId: String
) {
    var tracks: List<TrackShort> = listOf()
}