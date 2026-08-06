package com.yellastrodev.dwij.navigation

/**
 * Единый контракт навигации приложения.
 *
 * Файл не зависит от Android Navigation и может использоваться
 * Android- и desktop-слоями.
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

    const val OBJECT_PATTERN =
        "object/{$ARG_OBJECT_TYPE}/{$ARG_OBJECT_VALUE}"

    const val LOCAL_LIBRARY_PATTERN =
        "local-library/{$ARG_LOCAL_MODE}?" +
                "$ARG_LOCAL_PLAYLIST_ID={$ARG_LOCAL_PLAYLIST_ID}"

    const val PLAYLISTS_ADD_PATTERN =
        "$PLAYLISTS/add/{$ARG_TRACK_TO_ADD}"

    /**
     * Строит маршрут музыкального объекта.
     */
    fun objectRoute(
        type: String,
        value: String = "_",
    ): String =
        "object/" +
                type.encodeRouteComponent() +
                "/" +
                value
                    .ifEmpty { "_" }
                    .encodeRouteComponent()

    /**
     * Строит маршрут локальной библиотеки
     * или конкретного локального плейлиста.
     */
    fun localLibraryRoute(
        mode: String,
        playlistId: String? = null,
    ): String = buildString {
        append("local-library/")
        append(mode.encodeRouteComponent())

        if (playlistId != null) {
            append("?")
            append(ARG_LOCAL_PLAYLIST_ID)
            append("=")
            append(playlistId.encodeRouteComponent())
        }
    }

    /**
     * Открывает выбор плейлиста
     * для добавления конкретного Яндекс-трека.
     */
    fun playlistsAddRoute(
        trackId: String,
    ): String =
        "$PLAYLISTS/add/${trackId.encodeRouteComponent()}"
}

/**
 * RFC 3986 percent-encoding одного route/query-компонента.
 *
 * Кодирование выполняется по UTF-8 и не зависит от android.net.Uri.
 */
private fun String.encodeRouteComponent(): String = buildString {
    for (byte in this@encodeRouteComponent.encodeToByteArray()) {
        val value = byte.toInt() and 0xFF

        if (value.isUnreservedRouteByte()) {
            append(value.toChar())
        } else {
            append('%')
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0F])
        }
    }
}

private fun Int.isUnreservedRouteByte(): Boolean =
    this in 'a'.code..'z'.code ||
            this in 'A'.code..'Z'.code ||
            this in '0'.code..'9'.code ||
            this == '-'.code ||
            this == '.'.code ||
            this == '_'.code ||
            this == '~'.code

private const val HEX_DIGITS = "0123456789ABCDEF"
