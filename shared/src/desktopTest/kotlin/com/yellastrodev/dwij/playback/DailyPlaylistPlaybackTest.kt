package com.yellastrodev.dwij.playback

import com.yellastrodev.dwij.data.repo.withYandexReaction
import com.yellastrodev.dwij.data.entities.toPlaybackTrack
import com.yellastrodev.yamusicsdk.entities.YaTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Проверяет временные снимки, их source-ID реакции и воспроизведение неполной метадаты только из кеша. */
class DailyPlaylistPlaybackTest {

    /** Реакция временной песни сохраняет её ID и экземпляры, повторное снятие работает по тому же source ID. */
    @Test
    fun reactionUpdatesTemporarySongByYandexId() {
        val song = track(available = true).toDailyPlaylistSong()
        val liked = song.withYandexReaction("123", true)
        assertTrue(liked.isLiked)
        assertEquals(song.id, liked.id)
        assertEquals(song.instances, liked.instances)
        assertFalse(liked.withYandexReaction("123", false).isLiked)
        assertFalse(song.withYandexReaction("another-track", true).isLiked)
    }

    /** Даже available=true не разрешает сетевое воспроизведение записи без названия. */
    @Test
    fun incompleteMetadataRequiresCachedAudio() {
        val song = YaTrack(id = "123", available = true).toDailyPlaylistSong()
        assertEquals("Недоступный трек", song.title)
        assertNull(song.toPlaybackTrack { false })
        assertEquals("123", song.toPlaybackTrack { it == "123" }?.id)
    }

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
