package com.yellastrodev.dwij.data.cache

import com.yellastrodev.vkmusicsdk.VkAudio
import com.yellastrodev.vkmusicsdk.VkHlsRelay
import com.yellastrodev.vkmusicsdk.VkPlaylist
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** Постоянные VK-bundle, снимки и аккаунтные связи audio.add; метадата не содержит ключей и подписанных URL. */
class VkLocalStorage(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Метадата готового трека без подписанного audio URL и ключа доступа. */
    private data class StoredTrack(val audio: VkAudio, val root: String, val sizes: Map<String, Long>)
    /** Снимок порядка и повторов плейлиста для открытия без сети. */
    data class PlaylistSnapshot(val playlist: VkPlaylist, val tracks: List<VkAudio>, val hls: Map<String, Boolean> = emptyMap())

    init {
        directory.mkdirs()
        directory.listFiles { file -> file.isDirectory && file.name.startsWith("pending-") }
            ?.forEach { it.deleteRecursively() }
    }

    /** Находит полностью опубликованный трек; неполные/повреждённые bundle не считаются сохранёнными. */
    private fun stored(id: String): StoredTrack? = runCatching {
        val folder = trackDirectory(id)
        val record = readTrack(File(folder, "metadata.json"))
        require(record.audio.fullId == id && record.sizes.isNotEmpty())
        require(record.root in record.sizes)
        require(record.sizes.all { (name, size) ->
            name.matches(Regex("resource-[0-9]+\\.(m3u8|mp3|ts)")) && size > 0 &&
                File(folder, name).let { it.isFile && it.length() == size }
        })
        record
    }.getOrNull()

    /** Возвращает локальный корень для offline-плеера. */
    fun readyFile(id: String): File? = stored(id)?.let { File(trackDirectory(id), it.root) }

    /** Возвращает сохранённую метадату без обращения к VK. */
    fun audio(id: String): VkAudio? = stored(id)?.audio

    /** Перечисляет только готовые постоянные треки для поиска при недоступном VK. */
    fun audios(): List<VkAudio> = directory.listFiles { file -> file.isDirectory && file.name.startsWith("track-") }
        .orEmpty().mapNotNull { folder -> runCatching {
            val record = readTrack(File(folder, "metadata.json"))
            audio(record.audio.fullId)
        }.getOrNull() }

    /** Загружает в staging, затем атомарно публикует готовый каталог; отмена/отказ удаляют staging. */
    @Synchronized fun save(audio: VkAudio, relay: VkHlsRelay, onProgress: (Long, Long?) -> Unit): File {
        readyFile(audio.fullId)?.let { return it }
        directory.mkdirs()
        val staging = Files.createTempDirectory(directory.toPath(), "pending-").toFile()
        try {
            val root = relay.downloadAudio(audio.url, java.net.URI(audio.url).path.endsWith(".m3u8", true),
                staging, audio.fullId, onProgress)
            val sizes = staging.listFiles().orEmpty().associate { it.name to it.length() }
            val record = buildJsonObject {
                put("audio", json.encodeToJsonElement(audio.copy(url = "", accessKey = null)))
                put("root", root.name)
                put("sizes", buildJsonObject { sizes.forEach { (name, size) -> put(name, size) } })
            }
            File(staging, "metadata.json").writeText(record.toString(), Charsets.UTF_8)
            val target = trackDirectory(audio.fullId)
            if (target.exists()) check(target.deleteRecursively())
            try { Files.move(staging.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(staging.toPath(), target.toPath()) }
            return File(target, root.name)
        } finally { staging.deleteRecursively() }
    }

    /** Сохраняет состав до постановки загрузок в очередь; пропущенные треки останутся сетевыми. */
    @Synchronized fun rememberPlaylist(playlist: VkPlaylist, tracks: List<VkAudio>) {
        directory.mkdirs()
        val target = File(directory, "playlist-${hash(playlist.fullId)}.json")
        val temporary = File.createTempFile("playlist-", ".part", directory)
        try {
            val clean = playlist.copy(accessKey = null, original = playlist.original?.copy(accessKey = null))
            val formats = playlists().firstOrNull { it.playlist.fullId == playlist.fullId }?.hls.orEmpty() +
                tracks.filter { it.url.isNotBlank() }.associate {
                    it.fullId to java.net.URI(it.url).path.endsWith(".m3u8", ignoreCase = true)
                }
            val record = buildJsonObject {
                put("playlist", json.encodeToJsonElement(clean))
                put("tracks", json.encodeToJsonElement(tracks.map { it.copy(url = "", accessKey = null) }))
                put("hls", buildJsonObject {
                    formats.forEach { (id, isHls) -> put(id, isHls) }
                })
            }
            temporary.writeText(record.toString(), Charsets.UTF_8)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    /** Читает сохранённые снимки; повреждённая метадата одного списка не блокирует остальные. */
    @Synchronized fun playlists(): List<PlaylistSnapshot> = directory.listFiles { file ->
        file.isFile && file.name.startsWith("playlist-") && file.extension == "json"
    }.orEmpty().mapNotNull { file -> runCatching {
        val record = json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        PlaylistSnapshot(json.decodeFromJsonElement(record.getValue("playlist")),
            json.decodeFromJsonElement(record.getValue("tracks")),
            record["hls"]?.jsonObject?.mapValues { it.value.jsonPrimitive.boolean }.orEmpty())
    }.getOrNull() }

    /** Читает подтверждённые audio.add связи для конкретного аккаунта; повреждение не блокирует VK. */
    @Synchronized fun myTrackAliases(owner: Long): Map<String, String> = runCatching {
        val file = File(directory, "collection-${hash(owner.toString())}.json")
        json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject.mapValues { it.value.jsonPrimitive.content }
            .filter { (source, own) -> source.matches(Regex("-?[0-9]+_[0-9]+")) && own.matches(Regex("${owner}_[0-9]+")) }
    }.getOrDefault(emptyMap())

    /** Публикует связи ID после успешной мутации VK; разные аккаунты не используют чужую коллекцию. */
    @Synchronized fun rememberMyTrackAliases(owner: Long, aliases: Map<String, String>) {
        val target = File(directory, "collection-${hash(owner.toString())}.json")
        val temporary = File(directory, "${target.name}.tmp")
        try {
            temporary.writeText(buildJsonObject { aliases.forEach { (source, own) -> put(source, own) } }.toString(), Charsets.UTF_8)
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    /** Считает постоянные файлы, независимо от общего лимита кеша. */
    @Synchronized fun sizeBytes(): Long = directory.walkTopDown().filter(File::isFile).sumOf(File::length)

    /** Удаляет только принадлежащий этому хранилищу каталог, сериализуясь с загрузкой. */
    @Synchronized fun clear(): Boolean = directory.listFiles().orEmpty().map { it.deleteRecursively() }.all { it }

    /** Разбирает контейнер вручную; сериализаторы VkAudio принадлежат SDK, shared не требует нового compiler plugin. */
    private fun readTrack(file: File): StoredTrack {
        val record = json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        return StoredTrack(json.decodeFromJsonElement(record.getValue("audio")),
            record.getValue("root").jsonPrimitive.content,
            record.getValue("sizes").jsonObject.mapValues { it.value.jsonPrimitive.long })
    }

    /** Использует непрозрачное безопасное имя вместо VK-id в файловом пути. */
    private fun trackDirectory(id: String): File = File(directory, "track-${hash(id)}")

    /** Формирует устойчивое имя без source-id, ключей или URL в пути. */
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
