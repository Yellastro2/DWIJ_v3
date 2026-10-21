package com.yellastrodev.dwij.models

import com.yellastrodev.dwij.HomeMusicSource
import com.yellastrodev.dwij.data.entities.*
import com.yellastrodev.vkmusicsdk.VkAudio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Проверяет границу постоянных загрузок, фильтр источников и исключение сетевых вариантов из очереди. */
class DownloadedTracksTest {
    /** В ЯМ и VK показываются только сохранённые экземпляры своего источника. */
    @Test
    fun sourceFilterKeepsOnlyOwnSavedInstances() {
        val ya = yandex("ya")
        val vk = vk()
        val original = song("shared", listOf(ya, vk), preferred = vk.id)
        val yandexEntry = downloadedTrackEntries(listOf(original), HomeMusicSource.Yandex, { true }, { true }).single()
        val vkEntry = downloadedTrackEntries(listOf(original), HomeMusicSource.Vk, { true }, { true }).single()
        assertEquals(listOf(ya), yandexEntry.song.instances)
        assertEquals(ya.id, yandexEntry.song.preferredInstanceId)
        assertEquals(MusicSource.YANDEX, yandexEntry.origin)
        assertEquals(listOf(vk), vkEntry.song.instances)
        assertEquals(MusicSource.VK, vkEntry.origin)
        assertEquals(vk.id, original.preferredInstanceId)
        assertEquals(listOf(ya, vk), original.instances)
    }

    /** Онлайн-предпочтение песни не вытесняет её сохранённый VK-экземпляр. */
    @Test
    fun unsavedPreferredSourceCannotEnterDownloadedPlayback() {
        val ya = yandex("online")
        val savedVk = vk()
        val original = song("shared", listOf(ya, savedVk), preferred = ya.id)
        val entry = downloadedTrackEntries(listOf(original), HomeMusicSource.All, { false }, { true }).single()
        assertEquals(listOf(savedVk), entry.song.instances)
        assertEquals(savedVk.id, entry.song.preferredInstanceId)
        val playback = entry.song.toPlaybackTrack({ error("ЯМ не должен выбираться") }, { "http://127.0.0.1/saved-vk" })
        assertEquals(MusicSource.VK, playback?.source)
    }

    /** Общий список содержит обе сохранённые версии в одной строке и все независимые загрузки. */
    @Test
    fun combinedListIncludesAllDownloadsAndDeduplicatesCanonicalSong() {
        val shared = song("shared", listOf(yandex("ya"), vk()))
        val outsidePlaylists = song("standalone", listOf(yandex("other")))
        val result = downloadedTrackEntries(listOf(shared, outsidePlaylists, shared), HomeMusicSource.All, { true }, { true })
        assertEquals(setOf("shared", "standalone"), result.map { it.song.id }.toSet())
        assertEquals(2, result.size)
        assertEquals(2, result.single { it.song.id == "shared" }.song.instances.size)
    }

    /** Нескачанные записи, включая известные из поиска, отсутствуют; удаление файла убирает строку. */
    @Test
    fun missingDownloadsAreExcludedAndRemovedOnRescan() {
        val library = listOf(song("known", listOf(yandex("ya"), vk())))
        assertEquals(1, downloadedTrackEntries(library, HomeMusicSource.All, { true }, { false }).size)
        assertEquals(emptyList(), downloadedTrackEntries(library, HomeMusicSource.All, { false }, { false }))
    }

    /** Файлы MediaStore не становятся загрузками ЯМ/VK даже при наличии локального экземпляра у песни. */
    @Test
    fun ordinaryLocalMediaDoesNotEnterDownloads() {
        val local = TrackInstance.Local("local:1", LocalTrackEntity(
            instanceId = "local:1", mediaStoreId = 1L, volumeName = "external",
            contentUri = "content://media/external/audio/media/1", displayName = "local.mp3",
            title = "Локальный файл", artist = null, album = null, albumId = null,
            durationMs = 1000L, trackNumber = null, discNumber = null, year = null,
            mimeType = "audio/mpeg", sizeBytes = 10L, dateModifiedSeconds = 0L,
            relativePath = null, absolutePath = null,
        ))
        val localOnly = song("local", listOf(local))
        assertEquals(emptyList(), downloadedTrackEntries(listOf(localOnly), HomeMusicSource.All, { true }, { true }))
    }

    /** Недоступный на сервисе ЯМ-трек остаётся воспроизводимым из постоянной копии. */
    @Test
    fun unavailableYandexDownloadRemainsPlayableWithoutVk() {
        val ya = yandex("unavailable", available = false)
        val entry = downloadedTrackEntries(listOf(song("saved", listOf(ya))), HomeMusicSource.Yandex, { true }, { false }).single()
        val playback = entry.song.toPlaybackTrack({ true }, { error("VK не должен выбираться") })
        assertEquals(MusicSource.YANDEX, playback?.source)
        assertEquals("ya://unavailable", playback?.playbackUri)
    }

    /** Внутренняя коллекция не продолжает офлайн-очередь ЯМ-волной и не блокирует обычный shuffle. */
    @Test
    fun downloadedQueueHasNoYandexSeed() {
        val entry = downloadedTrackEntries(listOf(song("saved", listOf(vk()))), HomeMusicSource.Vk, { false }, { true }).single()
        val list = DownloadedTracklist(HomeMusicSource.Vk, "Скачанные", listOf(entry))
        assertNull(list.yandexWaveSeed())
        assertFalse(list.preserveQueueOrder())
        assertEquals(MusicSource.VK, list.originOf(entry.song.id))
    }

    /** Минимальный ЯМ-экземпляр без сети и Room. */
    private fun yandex(id: String, available: Boolean = true) =
        TrackInstance.Yandex("yandex:$id", dYaTrack(id, "ЯМ", available))

    /** Минимальный VK-экземпляр без временных сетевых URL. */
    private fun vk() = TrackInstance.Vk("vk:20_10", VkAudio(10, 20, title = "VK"))

    /** Каноническая песня; принадлежность плейлистам для скачанных треков не требуется. */
    private fun song(id: String, instances: List<TrackInstance>, preferred: String? = null) =
        Song(id, id, emptyList(), emptyList(), null, null, instances, preferred, false, false, false)
}
