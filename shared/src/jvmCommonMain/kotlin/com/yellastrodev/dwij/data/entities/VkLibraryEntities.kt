package com.yellastrodev.dwij.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.yellastrodev.vkmusicsdk.VkAudio
import com.yellastrodev.vkmusicsdk.VkPlaylist
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val vkMetadataJson = Json { ignoreUnknownKeys = true }

/** Постоянная source-метадата VK. URL потока, access_key и неоднозначный API like не сохраняются. */
@Entity(tableName = "vk_tracks")
data class VkTrackEntity(
    @PrimaryKey val fullId: String,
    val metadataJson: String,
    val isHls: Boolean = true,
) {
    /** Восстанавливает SDK-метадату с проверкой идентичности записи. */
    fun audio(): VkAudio = vkMetadataJson.decodeFromString<VkAudio>(metadataJson).also {
        require(it.fullId == fullId)
    }

    companion object {
        /** Оставляет долговечные метаданные; секреты доступа остаются только в памяти репозитория. */
        fun from(audio: VkAudio): VkTrackEntity = VkTrackEntity(
            audio.fullId,
            vkMetadataJson.encodeToString(audio.copy(url = "", accessKey = null, like = false)),
            audio.url.takeIf(String::isNotBlank)?.let {
                java.net.URI(it).path.endsWith(".m3u8", ignoreCase = true)
            } ?: true,
        )
    }
}

/**
 * Снимок аккаунта: сетка, упорядоченные составы плейлистов, «Мои треки» и audio.add связи.
 * null в myTracksJson означает «не загружено», а пустой JSON-список — подтверждённую пустую коллекцию.
 * Эти source-состояния не являются Song.isLiked или признаком наличия offline-файла.
 */
@Entity(tableName = "vk_library")
data class VkLibraryEntity(
    @PrimaryKey val accountId: Long,
    val myTracksJson: String? = null,
    val aliasesJson: String = "{}",
    val playlistTracksJson: String = "{}",
    val playlistsJson: String? = null,
) {
    /** Возвращает IDs личной коллекции в серверном порядке либо неизвестное состояние. */
    fun myTrackIds(): List<String>? = myTracksJson?.let { vkMetadataJson.decodeFromString<List<String>>(it) }

    /** Возвращает только подтверждённые source-id → personal-id связи, без fuzzy-сопоставления. */
    fun aliases(): Map<String, String> = vkMetadataJson.decodeFromString(aliasesJson)

    /** Возвращает известный состав отдельных плейлистов независимо от личной коллекции. */
    fun playlistTracks(): Map<String, List<String>> = vkMetadataJson.decodeFromString(playlistTracksJson)

    /** null означает неизвестную сетку; [] — успешно загруженный пустой список. */
    fun playlists(): List<VkPlaylist>? = playlistsJson?.let { vkMetadataJson.decodeFromString(it) }
}
