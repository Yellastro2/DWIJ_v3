package com.yellastrodev.dwij.data.cache

import com.yellastrodev.dwij.CacheManager
import com.yellastrodev.vkmusicsdk.VkCachedAudio
import com.yellastrodev.vkmusicsdk.VkCachedAudioResource
import com.yellastrodev.yamusicsdk.NoOpYamLogger
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Проверяет вытеснение целых VK-треков, общую давность сегментов, перезапуск и перенос плоского кеша. */
class VkTrackLruTest {
    /** Самая старая группа удаляется вместе с индексом и ресурсами всех её версий. */
    @Test fun evictsWholeTrackWithAllVersions() = fixture { directory ->
        var limit = 1_000_000L
        val manager = manager(directory) { limit }
        val root = File(directory, "tracks/vk-audio")
        val cache = VkAudioFileCache(FileCacheStore(root, manager), NoOpYamLogger)
        val first = audio('a', 'b')
        seed(cache, "1_1", first)
        val oldGroup = root.listFiles()!!.single { it.isDirectory }
        seed(cache, "1_1", audio('c'))
        val recent = audio('d', 'e')
        seed(cache, "1_2", recent)
        val recentGroup = root.listFiles()!!.single { it.isDirectory && it != oldGroup }
        oldGroup.setLastModified(1L)
        recentGroup.setLastModified(2L)
        limit = manager.getTotalSize() - 1L
        runBlocking { manager.ensureWithinLimit() }
        assertFalse(oldGroup.exists())
        assertNull(cache.readTrack("1_1"))
        first.resources.forEach { assertNull(cache.read(it.key)) }
        assertTrue(cache.isTrackComplete("1_2"))
        assertTrue(manager.getTotalSize() <= limit)
    }

    /** Чтение одного сегмента делает неделимый трек новым для LRU, даже если остальные файлы очень старые. */
    @Test fun readingOneSegmentRefreshesWholeTrack() = fixture { directory ->
        var limit = 1_000_000L
        val manager = manager(directory) { limit }
        val root = File(directory, "tracks/vk-audio")
        val cache = VkAudioFileCache(FileCacheStore(root, manager), NoOpYamLogger)
        val first = audio('a', 'b')
        seed(cache, "1_1", first)
        val firstGroup = root.listFiles()!!.single { it.isDirectory }
        seed(cache, "1_2", audio('c', 'd'))
        val secondGroup = root.listFiles()!!.single { it.isDirectory && it != firstGroup }
        firstGroup.walkTopDown().filter { it.isFile }.forEach { it.setLastModified(1L) }
        firstGroup.setLastModified(1L)
        secondGroup.setLastModified(2L)
        assertNotNull(cache.read(first.resources.first().key))
        limit = manager.getTotalSize() - 1L
        runBlocking { manager.ensureWithinLimit() }
        assertTrue(firstGroup.exists())
        assertFalse(secondGroup.exists())
        assertTrue(cache.isTrackComplete("1_1"))
    }

    /** До готовности офлайн-индекса частичные ресурсы уже хранятся и вытесняются одной группой. */
    @Test fun partialTrackIsGroupedBeforeIndexPublication() = fixture { directory ->
        var limit = 1_000_000L
        val manager = manager(directory) { limit }
        val root = File(directory, "tracks/vk-audio")
        val cache = VkAudioFileCache(FileCacheStore(root, manager), NoOpYamLogger)
        val partial = audio('a', 'b', 'c')
        cache.registerTrack("1_1", partial)
        cache.write(partial.resources.first().key, byteArrayOf(1, 2, 3))
        val partialGroup = root.listFiles()!!.single { it.isDirectory }
        assertNull(cache.readTrack("1_1"))
        seed(cache, "1_2", audio('d'))
        partialGroup.setLastModified(1L)
        limit = manager.getTotalSize() - 1L
        runBlocking { manager.ensureWithinLimit() }
        assertFalse(partialGroup.exists())
        assertNull(cache.read(partial.resources.first().key))
        assertTrue(cache.isTrackComplete("1_2"))
    }

    /** После перезапуска размер и группы учитываются ещё до открытия индекса первой песни. */
    @Test fun restartCountsAndEvictsGroupsBeforeAnyTrackRead() = fixture { directory ->
        var limit = 1_000_000L
        val firstManager = manager(directory) { limit }
        val root = File(directory, "tracks/vk-audio")
        val cache = VkAudioFileCache(FileCacheStore(root, firstManager), NoOpYamLogger)
        seed(cache, "1_1", audio('a', 'b'))
        val firstGroup = root.listFiles()!!.single { it.isDirectory }
        seed(cache, "1_2", audio('c'))
        firstGroup.setLastModified(1L)
        val size = firstManager.getTotalSize()
        val reopenedManager = manager(directory) { limit }
        val reopened = VkAudioFileCache(FileCacheStore(root, reopenedManager), NoOpYamLogger)
        assertEquals(size, reopenedManager.getTotalSize())
        limit = size - 1L
        runBlocking { reopenedManager.ensureWithinLimit() }
        assertFalse(firstGroup.exists())
        assertTrue(reopened.isTrackComplete("1_2"))
    }

    /** Запись после вытеснения воссоздаёт группу; трек больше лимита не оставляет сегментные обрывки. */
    @Test fun oversizedTrackIsRemovedAndCanBeCachedAgain() = fixture { directory ->
        var limit = 1L
        val manager = manager(directory) { limit }
        val root = File(directory, "tracks/vk-audio")
        val cache = VkAudioFileCache(FileCacheStore(root, manager), NoOpYamLogger)
        val track = audio('a')
        cache.registerTrack("1_1", track)
        cache.write(track.resources.single().key, byteArrayOf(1, 2))
        assertEquals(0L, manager.getTotalSize())
        assertTrue(root.listFiles().orEmpty().isEmpty())
        limit = 1_000_000L
        seed(cache, "1_1", track)
        assertTrue(cache.isTrackComplete("1_1"))
    }

    /** Старый плоский индекс и сегменты перемещаются без роста размера и без потери при тесном лимите. */
    @Test fun legacyCacheMovesIntoGroupWithoutDuplicatingBytes() = fixture { directory ->
        var limit = 1_000_000L
        val manager = manager(directory) { limit }
        val root = File(directory, "tracks/vk-audio")
        val legacy = FileCacheStore(root, manager)
        val track = audio('a', 'b')
        runBlocking {
            track.resources.forEach { legacy.write(it.key, payload(byteArrayOf(1, 2, 3))) }
            legacy.write("vk-audio-index-v1:1_1", payload(Json.encodeToString(track).toByteArray()))
        }
        val size = manager.getTotalSize()
        limit = size
        val cache = VkAudioFileCache(FileCacheStore(root, manager), NoOpYamLogger)
        assertEquals(track, cache.readTrack("1_1"))
        assertEquals(size, manager.getTotalSize())
        assertTrue(root.listFiles()!!.none { it.isFile })
        assertEquals(1, root.listFiles()!!.count { it.isDirectory })
        runBlocking { manager.ensureWithinLimit() }
        assertTrue(cache.isTrackComplete("1_1"))
    }

    /** Создаёт одну конечную HLS-версию с непрозрачными, различающимися ресурсами. */
    private fun audio(vararg keys: Char): VkCachedAudio {
        val resources = keys.mapIndexed { index, key -> VkCachedAudioResource("resource-$index.ts", key.toString().repeat(64), hlsSegment = true) }
        return VkCachedAudio(resources, "#EXTM3U\n" + resources.joinToString("") { "#EXTINF:1,\n${it.name}\n" } + "#EXT-X-ENDLIST\n")
    }

    /** Публикует полную группу, как relay после получения всех сегментов. */
    private fun seed(cache: VkAudioFileCache, id: String, audio: VkCachedAudio) {
        cache.registerTrack(id, audio)
        audio.resources.forEach { cache.write(it.key, byteArrayOf(1, 2, 3)) }
        assertTrue(cache.writeTrack(id, audio))
    }

    /** Общий менеджер с динамическим лимитом для граничных сценариев. */
    private fun manager(directory: File, limit: () -> Long) =
        CacheManager(File(directory, "tracks"), File(directory, "covers"), limit, NoOpYamLogger)

    /** Формат прежнего ресурса нужен только для проверки реального переноса старых файлов. */
    private fun payload(bytes: ByteArray): ByteArray = ByteBuffer.allocate(36 + bytes.size).putInt(1)
        .put(MessageDigest.getInstance("SHA-256").digest(bytes)).put(bytes).array()

    /** Изолирует файловую систему теста и удаляет только созданный временный каталог. */
    private fun fixture(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("vk-track-lru-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
