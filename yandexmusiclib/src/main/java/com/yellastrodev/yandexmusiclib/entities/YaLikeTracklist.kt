package com.yellastrodev.yandexmusiclib.entities

import com.yellastrodev.yandexmusiclib.CONSTANTS.Companion.LIKED_ID
import kotlinx.serialization.Serializable

@Serializable
class YaLikeTracklist(
    val playlistUuid: String,
    val uid: Int,
    val revision: Int,
    val tracks: List<TrackShort> = listOf(),
)