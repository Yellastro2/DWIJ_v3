package com.yellastrodev.dwij.data.cache

import com.yellastrodev.vkmusicsdk.VkAudio
import com.yellastrodev.vkmusicsdk.VkPlaylist
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

/** Проверяет восстановление offline-метадаты, дубли состава и отказ от неполного bundle. */
class VkLocalStorageTest {
    /** Связи audio.add переживают перезапуск и отделены по аккаунтам без URL или ключей доступа. */
    @Test fun collectionAliasesSurviveRestartAndRemainAccountScoped() {
        val directory = Files.createTempDirectory("vk-collection-aliases").toFile()
        try {
            VkLocalStorage(directory).rememberMyTrackAliases(42, mapOf("-7_9" to "42_100"))
            val restored = VkLocalStorage(directory)
            assertEquals(mapOf("-7_9" to "42_100"), restored.myTrackAliases(42))
            assertTrue(restored.myTrackAliases(43).isEmpty())
        } finally { directory.deleteRecursively() }
    }

    /** Снимок сохраняет повторы, удаляет URL/access_key; ready требует весь набор файлов. */
    @Test fun snapshotsAndReadyBundlesSurviveRestart() {
        val directory = Files.createTempDirectory("vk-local-storage").toFile()
        try {
            val storage = VkLocalStorage(directory)
            val audio = VkAudio(2, 1, title = "Тест", url = "https://media.example/?token=secret", accessKey = "private")
            storage.rememberPlaylist(VkPlaylist(3, 1, title = "Список", accessKey = "private"), listOf(audio, audio))
            val hash = MessageDigest.getInstance("SHA-256").digest("1_2".toByteArray()).joinToString("") { "%02x".format(it) }
            val folder = File(directory, "track-$hash").apply { mkdirs() }
            val file = File(folder, "resource-0.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            File(folder, "metadata.json").writeText("""{"audio":{"id":2,"owner_id":1,"title":"Тест"},"root":"resource-0.mp3","sizes":{"resource-0.mp3":3}}""")
            val pending = File(directory, "pending-incomplete").apply { mkdirs() }
            val reopened = VkLocalStorage(directory)
            assertFalse(pending.exists())
            val snapshot = reopened.playlists().single()
            assertEquals(listOf("1_2", "1_2"), snapshot.tracks.map { it.fullId })
            assertTrue(snapshot.tracks.all { it.url.isEmpty() && it.accessKey == null })
            assertNull(snapshot.playlist.accessKey)
            assertEquals(false, snapshot.hls["1_2"])
            reopened.rememberPlaylist(snapshot.playlist, snapshot.tracks)
            assertEquals(false, reopened.playlists().single().hls["1_2"])
            assertEquals(file, reopened.readyFile("1_2"))
            assertEquals("Тест", reopened.audios().single().title)
            file.writeBytes(byteArrayOf(1))
            assertNull(reopened.readyFile("1_2"))
            assertTrue(reopened.audios().isEmpty())
            assertTrue(reopened.clear())
            assertEquals(0L, reopened.sizeBytes())
        } finally { directory.deleteRecursively() }
    }
}
