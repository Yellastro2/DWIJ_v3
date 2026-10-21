package com.yellastrodev.dwij.data.cache

import com.yellastrodev.vkmusicsdk.VkAudioCache
import com.yellastrodev.vkmusicsdk.VkCachedAudio
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import com.yellastrodev.yamusicsdk.YamLogger
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/** Общий LRU-кеш VK: трек хранится и вытесняется целым каталогом, ресурсы/индекс защищены SHA-256. */
class VkAudioFileCache(private val store: FileCacheStore, private val logger: YamLogger) : VkAudioCache {
    private val json = Json { ignoreUnknownKeys = true }
    private val tracks = ConcurrentHashMap<String, FileCacheStore>()
    private val resourceStores = ConcurrentHashMap<String, FileCacheStore>()
    init { store.enableGrouping() }

    /** Чтение сегмента обновляет давность всей группы; старые неизвестные записи читаются из плоского кеша. */
    override fun read(key: String): ByteArray? = readPayload(resourceStores[key] ?: store, key)

    /** Проверяет формат и SHA-256; повреждённая запись удаляется только из своего хранилища. */
    private fun readPayload(storage: FileCacheStore, key: String): ByteArray? {
        val stored = storage.read(key) ?: return null
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
        runBlocking { storage.remove(key) }
        return null
    }

    /** Публикует ресурс в группе, объявленной relay до первой записи; неизвестные старые потребители остаются совместимыми. */
    override fun write(key: String, bytes: ByteArray) {
        val stored = checkedPayload(bytes)
        runBlocking { (resourceStores[key] ?: store).write(key, stored) }
        logger.debug("VkAudioFileCache", "[writeVkAudioCache] Аудио VK передано в кеш: ${bytes.size} байт")
    }

    /** Проверяет наличие без чтения аудиобайтов; перед локальным открытием read проверяет SHA-256 всех частей. */
    override fun contains(key: String): Boolean = (resourceStores[key] ?: store).contains(key)

    /** Объявляет неделимую группу уже для частичного трека; старые ресурсы перемещаются без копирования/перезагрузки. */
    override fun registerTrack(audioId: String, audio: VkCachedAudio) {
        require(audio.isValid()) { "Некорректная группа кеша VK" }
        val group = trackStore(audioId)
        audio.resources.forEach { resource ->
            if (resourceStores.put(resource.key, group) !== group) {
                try {
                    runBlocking { group.adopt(resource.key, store) }
                } catch (error: Exception) {
                    resourceStores.remove(resource.key, group)
                    throw error
                }
            }
        }
    }

    /** Восстанавливает индекс и привязки сегментов после перезапуска; прежний плоский кеш переносится в группу. */
    override fun readTrack(audioId: String): VkCachedAudio? {
        val key = trackIndexKey(audioId)
        val group = trackStore(audioId)
        val storage = if (group.contains(key)) group else store
        val bytes = readPayload(storage, key) ?: return null
        val audio = runCatching { json.decodeFromString<VkCachedAudio>(bytes.toString(Charsets.UTF_8)) }.getOrNull()
        if (audio?.isValid() == true) {
            registerTrack(audioId, audio)
            if (storage === store) runBlocking { group.adopt(key, store) }
            return audio
        }
        runBlocking { storage.remove(key) }
        logger.warning("VkAudioFileCache", "[readTrack] Повреждённое описание кеша VK удалено")
        return null
    }

    /** Заменяет индекс полной версии внутри группы; все старые и новые версии вытесняются вместе. */
    override fun writeTrack(audioId: String, audio: VkCachedAudio): Boolean {
        require(audio.isValid()) { "Некорректное описание кеша VK" }
        registerTrack(audioId, audio)
        val key = trackIndexKey(audioId)
        val payload = checkedPayload(json.encodeToString(audio).toByteArray(Charsets.UTF_8))
        val group = trackStore(audioId)
        runBlocking { group.write(key, payload, replaceExisting = true) }
        return group.contains(key)
    }

    /** Одна группа соответствует source-id, а не временной ссылке или версии HLS. */
    private fun trackStore(audioId: String): FileCacheStore = tracks.getOrPut(audioId) { store.group(audioId) }

    /** Отделяет указатель полного трека от ключей неизменяемых аудиоресурсов. */
    private fun trackIndexKey(audioId: String): String = "vk-audio-index-v1:$audioId"

    /** Одинаковая проверяемая оболочка защищает аудиобайты и локальное описание от обрыва записи. */
    private fun checkedPayload(bytes: ByteArray): ByteArray =
        ByteBuffer.allocate(36 + bytes.size).putInt(1).put(checksum(bytes)).put(bytes).array()

    /** Вычисляет контрольную сумму готового расшифрованного ресурса. */
    private fun checksum(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
