package com.yellastrodev.dwij.playback

import com.yellastrodev.dwij.data.entities.toPlaybackTrack
import com.yellastrodev.yamusicsdk.entities.YaTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Проверяет воспроизведение временных снимков без данных фонотеки и связей плейлиста. */
class DailyPlaylistPlaybackTest {
    /** Временная песня передаёт плееру реальный ЯМ-ID, но не приобретает связи плейлиста. */
    @Test
    fun temporarySongPlaysWithoutPlaylistMembership() {
        val song = track(available = true).toDailyPlaylistSong()
        val playback = requireNotNull(song.toPlaybackTrack { false })
        assertEquals("123", playback.id)
        assertEquals("ya://123", playback.playbackUri)
        assertEquals(120000L, playback.durationMs)
        assertTrue(requireNotNull(playback.yandexTrack).playlists.isEmpty())
        assertTrue(song.id.startsWith("daily:"))
        assertFalse(song.isLiked)
    }

    /** Недоступный трек допускается существующим выборщиком только при наличии аудио в кеше. */
    @Test
    fun unavailableTrackRequiresCachedAudio() {
        val song = track(available = false).toDailyPlaylistSong()
        assertNull(song.toPlaybackTrack { false })
        assertEquals("123", song.toPlaybackTrack { it == "123" }?.id)
    }

    /** Создаёт минимальный трек из ответа SDK. */
    private fun track(available: Boolean) = YaTrack(
        id = "123",
        title = "Трек дня",
        available = available,
        durationMs = 120000,
        artists = emptyList(),
        albums = emptyList(),
    )
}
