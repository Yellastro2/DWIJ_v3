package com.yellastrodev.dwij.playback

import com.yellastrodev.dwij.data.entities.*
import com.yellastrodev.vkmusicsdk.VkAudio
import com.yellastrodev.yamusicsdk.entities.YaTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Проверяет границу постоянной метадаты и временного VK URI, сохраняя выбор других источников. */
class VkPlaybackSelectionTest {
    /** Один постоянный экземпляр допускает новый URI на каждом запуске. */
    @Test
    fun resolverDoesNotBecomeSongMetadata() {
        val audio = VkAudio(10, 20, title = "Song")
        val record = VkTrackEntity.from(audio.copy(url = "https://audio.example/song.m3u8", accessKey = "synthetic-key", like = true))
        val instance = TrackInstance.Vk("vk:20_10", record.audio(), record.isHls)
        val song = Song("vk:20_10", "Song", emptyList(), emptyList(), null, null,
            listOf(instance), instance.id, false, false, false)
        val first = song.toPlaybackTrack({ false }, { "http://127.0.0.1:1111/one" })
        val second = song.toPlaybackTrack({ false }, { "http://127.0.0.1:2222/two" })
        assertEquals("http://127.0.0.1:1111/one", first?.playbackUri)
        assertEquals("http://127.0.0.1:2222/two", second?.playbackUri)
        assertEquals("", instance.track.url)
        assertNull(instance.track.accessKey)
        assertFalse(instance.track.like)
        assertNull(song.toPlaybackTrack { false })
    }

    /** Если предпочтительный VK недоступен, доступный Яндекс сохраняет прежний fallback. */
    @Test
    fun unavailableVkFallsBackToYandex() {
        val yandex = YaTrack("123", "Song", available = true, artists = emptyList(), albums = emptyList())
            .toDailyPlaylistSong()
        val vk = TrackInstance.Vk("vk:20_10", VkAudio(10, 20))
        val mixed = yandex.copy(instances = listOf(vk) + yandex.instances, preferredInstanceId = vk.id)
        assertEquals(MusicSource.YANDEX, mixed.toPlaybackTrack({ false }, { null })?.source)
        var calls = 0
        val ordinary = yandex.copy(instances = yandex.instances + vk)
        assertEquals("ya://123", ordinary.toPlaybackTrack({ false }, { calls++; "http://127.0.0.1/vk" })?.playbackUri)
        assertEquals(0, calls)
    }
}
