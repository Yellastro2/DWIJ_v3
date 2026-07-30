package com.yellastrodev.yandexmusiclib.playlists

import com.yellastrodev.yandexmusiclib.entities.YaPlaylist
import com.yellastrodev.yandexmusiclib.entities.YaTrack

/**
 * Плейлист вместе с раскрытыми моделями треков из ответа его detail endpoint.
 */
data class PlaylistDetails(
    val playlist: YaPlaylist,
    val tracks: List<YaTrack>
)

/**
 * Доступность создаваемого плейлиста.
 */
enum class PlaylistVisibility(internal val apiValue: String) {
    PUBLIC("public"),
    PRIVATE("private")
}
