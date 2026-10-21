package com.yellastrodev.dwij.data.cache

import com.yellastrodev.dwij.CacheManager
import com.yellastrodev.yamusicsdk.NoOpYamLogger
import com.yellastrodev.vkmusicsdk.VkCachedAudio
import com.yellastrodev.vkmusicsdk.VkCachedAudioResource
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Проверяет ресурсы/индекс полного VK-трека, восстановление повреждений и общий LRU-лимит. */
class VkAudioFileCacheTest {
    /** Индекс переживает новый экземпляр, атомарно меняет версию и теряет готовность при LRU-пропаже сегмента. */
    @Test fun trackIndexSurvivesRestartReplacesVersionAndChecksEveryResource() {
        val directory = Files.createTempDirectory("vk-track-index-test").toFile()
        try {
            var limit = 1024L * 1024L
            val tracks = File(directory, "tracks")
            val manager = CacheManager(tracks, File(directory, "covers"), { limit }, NoOpYamLogger)
            val folder = File(tracks, "vk-audio")
            val store = FileCacheStore(folder, manager)
            val cache = VkAudioFileCache(store, NoOpYamLogger)
            val firstKey = "a".repeat(64)
            val secondKey = "b".repeat(64)
            val first = VkCachedAudio(listOf(VkCachedAudioResource("resource-0.mp3", firstKey, "audio/mpeg")))
            val second = VkCachedAudio(listOf(VkCachedAudioResource("resource-0.mp3", secondKey, "audio/mpeg")))
            cache.write(firstKey, byteArrayOf(1, 2))
            assertTrue(cache.writeTrack("1_2", first))
            val reopened = VkAudioFileCache(FileCacheStore(folder, manager), NoOpYamLogger)
            assertEquals(first, reopened.readTrack("1_2"))
            assertTrue(reopened.isTrackComplete("1_2"))
            cache.write(secondKey, byteArrayOf(3, 4))
            assertTrue(cache.writeTrack("1_2", second))
            assertEquals(second, reopened.readTrack("1_2"))
            assertEquals(3, folder.walkTopDown().count { it.isFile })
            kotlinx.coroutines.runBlocking { store.group("1_2").remove(secondKey) }
            assertFalse(reopened.isTrackComplete("1_2"))
            cache.write(secondKey, byteArrayOf(3, 4))
            limit = 0L
            cache.write("force-trim", byteArrayOf(5))
            assertEquals(0L, manager.getTotalSize())
            assertNull(reopened.readTrack("1_2"))
        } finally { directory.deleteRecursively() }
    }

    /** Обрыв аудио не скрывается исправным индексом: SHA-256 проверяется перед готовностью полного трека. */
    @Test fun corruptedAudioInvalidatesCompleteTrack() {
        val directory = Files.createTempDirectory("vk-track-corruption-test").toFile()
        try {
            val tracks = File(directory, "tracks")
            val manager = CacheManager(tracks, File(directory, "covers"), { 1024L * 1024L }, NoOpYamLogger)
            val folder = File(tracks, "vk-audio")
            val cache = VkAudioFileCache(FileCacheStore(folder, manager), NoOpYamLogger)
            val key = "a".repeat(64)
            val audio = VkCachedAudio(listOf(VkCachedAudioResource("resource-0.mp3", key, "audio/mpeg")))
            cache.write(key, byteArrayOf(1, 2))
            cache.writeTrack("1_2", audio)
            val audioFile = folder.walkTopDown().single { it.isFile && it.length() == 38L }
            audioFile.writeBytes(byteArrayOf(0))
            assertNotNull(cache.readTrack("1_2"))
            assertFalse(cache.isTrackComplete("1_2"))
            assertNull(cache.read(key))
        } finally { directory.deleteRecursively() }
    }

    /** Новый экземпляр читает прежний файл, повреждение перезагружается, LRU освобождает общий кеш. */
    @Test fun persistentCacheRecoversCorruptionAndRespectsSharedLimit() {
        val directory = Files.createTempDirectory("vk-audio-cache-test").toFile()
        try {
            var limit = 1024L
            val tracks = File(directory, "tracks")
            val manager = CacheManager(tracks, File(directory, "covers"), { limit }, NoOpYamLogger)
            val vk = File(tracks, "vk-audio")
            val cache = VkAudioFileCache(FileCacheStore(vk, manager), NoOpYamLogger)
            val audio = byteArrayOf(1, 2, 3)
            cache.write("segment", audio)
            val reopened = VkAudioFileCache(FileCacheStore(vk, manager), NoOpYamLogger)
            assertContentEquals(audio, reopened.read("segment"))
            assertEquals(39L, manager.getTotalSize())
            vk.listFiles()!!.single().writeBytes(byteArrayOf(0))
            assertNull(reopened.read("segment"))
            reopened.write("segment", audio)
            assertContentEquals(audio, reopened.read("segment"))
            val oldYandex = File(tracks, "old.cache").apply { writeBytes(ByteArray(20)); setLastModified(1) }
            limit = 78L
            reopened.write("other-segment", audio)
            assertFalse(oldYandex.exists())
            assertTrue(manager.getTotalSize() <= limit)
        } finally {
            directory.deleteRecursively()
        }
    }
}
