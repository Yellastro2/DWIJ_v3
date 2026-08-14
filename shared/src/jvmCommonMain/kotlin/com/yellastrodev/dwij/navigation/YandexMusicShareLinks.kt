package com.yellastrodev.dwij.navigation

/** Собирает публичные веб-ссылки на сущности Яндекс Музыки. */
internal object YandexMusicShareLinks {
    fun playlist(playlistUuid: String): String = "$BASE_URL/playlists/$playlistUuid"

    fun artist(artistId: Int): String = "$BASE_URL/artist/$artistId"

    fun album(albumId: Int): String = "$BASE_URL/album/$albumId"

    fun track(trackId: String): String = "$BASE_URL/track/$trackId"

    private const val BASE_URL = "https://music.yandex.ru"
}
