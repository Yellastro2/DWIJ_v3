package com.yellastrodev.dwij.navigation

import android.net.Uri

/**
 * Единый контракт Compose-навигации приложения.
 *
 * Аргументы кодируются перед добавлением в route, чтобы названия, UUID и внешние идентификаторы
 * не могли случайно превратиться в отдельные сегменты пути.
 */
object DwijDestination {
    const val HOME = "home"
    const val PLAYLISTS = "playlists"
    const val PLAYER = "player"
    const val SONG_MATCHES = "song-matches"
    const val SETTINGS = "settings"

    const val OBJECT_TYPE_TRACK = "track"
    const val OBJECT_TYPE_PLAYLIST = "playlist"
    const val OBJECT_TYPE_TRACKLIST = "tracklist"
    const val OBJECT_TYPE_ARTIST = "artist"

    const val LOCAL_MODE_PLAYLISTS = "playlists"
    const val LOCAL_MODE_ALL_TRACKS = "all_tracks"
    const val LOCAL_MODE_PLAYLIST = "playlist"

    const val ARG_OBJECT_TYPE = "objectType"
    const val ARG_OBJECT_VALUE = "objectValue"
    const val ARG_LOCAL_MODE = "localMode"
    const val ARG_LOCAL_PLAYLIST_ID = "playlistId"
    const val ARG_TRACK_TO_ADD = "trackToAdd"

    const val OBJECT_PATTERN = "object/{$ARG_OBJECT_TYPE}/{$ARG_OBJECT_VALUE}"
    const val LOCAL_LIBRARY_PATTERN =
        "local-library/{$ARG_LOCAL_MODE}?$ARG_LOCAL_PLAYLIST_ID={$ARG_LOCAL_PLAYLIST_ID}"
    const val PLAYLISTS_ADD_PATTERN = "$PLAYLISTS/add/{$ARG_TRACK_TO_ADD}"

    /** Строит маршрут музыкального объекта, сохраняя прежние значения type/value. */
    fun objectRoute(type: String, value: String = "_"): String =
        "object/${Uri.encode(type)}/${Uri.encode(value.ifEmpty { "_" })}"

    /** Строит маршрут локальной библиотеки или конкретного локального плейлиста. */
    fun localLibraryRoute(mode: String, playlistId: String? = null): String = buildString {
        append("local-library/")
        append(Uri.encode(mode))
        if (playlistId != null) {
            append("?")
            append(ARG_LOCAL_PLAYLIST_ID)
            append("=")
            append(Uri.encode(playlistId))
        }
    }

    /** Открывает выбор плейлиста для добавления конкретного Яндекс-трека. */
    fun playlistsAddRoute(trackId: String): String =
        "$PLAYLISTS/add/${Uri.encode(trackId)}"
}
