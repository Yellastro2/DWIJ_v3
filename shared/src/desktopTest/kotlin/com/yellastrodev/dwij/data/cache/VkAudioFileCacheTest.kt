package com.yellastrodev.dwij.data.cache

import com.yellastrodev.dwij.CacheManager
import com.yellastrodev.yamusicsdk.NoOpYamLogger
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Проверяет постоянство аудио, восстановление повреждений и общий лимит с другими источниками. */
class VkAudioFileCacheTest {
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
