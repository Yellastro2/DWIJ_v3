package com.yellastrodev.yandexmusiclib.entities

import kotlinx.serialization.Serializable

@Serializable
class YaLikeTracklist(
    val playlistUuid: String,
    val uid: Int,
    val revision: Int,
    val tracks: List<TrackShort> = listOf(),
)