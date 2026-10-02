package com.yellastrodev.dwij.data.cache

import com.yellastrodev.vkmusicsdk.VkAudioCache
import com.yellastrodev.yamusicsdk.YamLogger
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking

/** Постоянный кеш VK в общем LRU-хранилище; контрольная сумма обнаруживает обрыв/повреждение файла. */
class VkAudioFileCache(private val store: FileCacheStore, private val logger: YamLogger) : VkAudioCache {
    /** Проверяет формат и SHA-256 до передачи аудио плееру; повреждённый файл удаляется для перезагрузки. */
    override fun read(key: String): ByteArray? {
        val stored = store.read(key) ?: return null
        if (stored.size >= 36) {
            val buffer = ByteBuffer.wrap(stored)
            val version = buffer.int
            val expected = ByteArray(32).also { buffer.get(it) }
            val audio = ByteArray(buffer.remaining()).also { buffer.get(it) }
            if (version == 1 && audio.isNotEmpty() && MessageDigest.isEqual(expected, checksum(audio))) {
                logger.debug("VkAudioFileCache", "[readVkAudioCache] Аудио VK из кеша: ${audio.size} байт")
                return audio
            }
        }
        logger.warning("VkAudioFileCache", "[readVkAudioCache] Повреждённая запись VK удалена для повторной загрузки")
        runBlocking { store.remove(key) }
        return null
    }

    /** Выполняется только HTTP-worker relay; FileCacheStore атомарно публикует файл и применяет общий лимит. */
    override fun write(key: String, bytes: ByteArray) {
        val stored = ByteBuffer.allocate(36 + bytes.size).putInt(1).put(checksum(bytes)).put(bytes).array()
        runBlocking { store.write(key, stored) }
        logger.debug("VkAudioFileCache", "[writeVkAudioCache] Аудио VK передано в кеш: ${bytes.size} байт")
    }

    /** Вычисляет контрольную сумму готового расшифрованного ресурса. */
    private fun checksum(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
