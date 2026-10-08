package com.yellastrodev.dwij.data.repo

import com.yellastrodev.vkmusicsdk.VkApiClient
import com.yellastrodev.vkmusicsdk.VkAudio
import com.yellastrodev.vkmusicsdk.VkPlaylist
import com.yellastrodev.vkmusicsdk.VkPlaylistPermissions
import kotlinx.serialization.json.*

/** Пространство виртуальных треклистов рекомендаций, независимое от личных плейлистов. */
internal const val VK_RECOMMENDATION_PREFIX = "vkrec:"

/** Метаданные персональной карточки; состав запрашивается только при открытии. */
internal data class VkRecommendationCard(val id: String, val title: String, val playlist: VkPlaylist? = null)

/** Читает каталог и конечные рекомендации без запросов VK Микса и без изменения личной фонотеки. */
internal class VkRecommendationCatalog(private val client: VkApiClient) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** Получает обложки только трёх generated-подборок, не загружая их составы. */
    suspend fun cards(): List<VkRecommendationCard> {
        val root = client.getAudioCatalog().jsonObject
        val playlists = (root["playlists"] as? JsonArray)?.let {
            json.decodeFromJsonElement<List<VkPlaylist>>(it)
        }.orEmpty()
        return playlists.filter { it.id in setOf(-21L, -24L, -23L) }.map {
            VkRecommendationCard("${VK_RECOMMENDATION_PREFIX}generated:${it.id}", it.title, it)
        }
    }

    /** Загружает только выбранный список, получая актуальный ключ подборки из метаданных каталога. */
    suspend fun content(routeId: String): VkPlaylistContent {
        val key = routeId.removePrefix(VK_RECOMMENDATION_PREFIX)
        if (key == "recommendations") {
            val page = client.getRecommendations()
            return VkPlaylistContent(virtualPlaylist(routeId, "Рекомендации"), page.items)
        }
        require(key.startsWith("generated:")) { "Неизвестная подборка VK" }
        val selected = cards().firstOrNull { it.id == routeId }?.playlist
            ?: throw IllegalArgumentException("Подборка VK недоступна")
        val tracks = client.getPlaylistTracks(selected)
        return VkPlaylistContent(selected.copy(permissions = VkPlaylistPermissions(edit = false, delete = false)), tracks)
    }
    /** Виртуальный список доступен для воспроизведения и сохранения, но не для серверных мутаций. */
    private fun virtualPlaylist(routeId: String, title: String): VkPlaylist = VkPlaylist(
        id = -(java.util.zip.CRC32().apply { update(routeId.toByteArray(Charsets.UTF_8)) }.value + 100),
        ownerId = 0, title = title,
        permissions = VkPlaylistPermissions(edit = false, delete = false),
    )
}
