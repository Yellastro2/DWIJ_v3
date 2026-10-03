package com.yellastrodev.dwij.data.entities

/**
 * Внутренний транспорт выбранного [TrackInstance] к Media3.
 * Экраны и публичная очередь работают с [Song], а не с этим DTO.
 */
data class PlaybackTrack(
    val id: String,
    val songId: String,
    val instanceId: String,
    val source: MusicSource,
    val title: String,
    val artistNames: List<String>,
    val durationMs: Long?,
    val playbackUri: String,
    val artworkUri: String?,
    val yandexTrack: dYaTrack? = null,
    val localTrack: LocalTrackEntity? = null,
)

/** Сохраняет прежний контракт для очередей без VK-resolver. */
fun Song.toPlaybackTrack(isYandexCached: (String) -> Boolean): PlaybackTrack? =
    toPlaybackTrack(isYandexCached, { null })

/** Выбирает экземпляр; VK-resolver создаёт адрес текущего relay, не изменяя постоянную Song. */
fun Song.toPlaybackTrack(
    isYandexCached: (String) -> Boolean,
    resolveVkUri: (TrackInstance.Vk) -> String?,
): PlaybackTrack? {
    val vkUris = mutableMapOf<String, String?>()
    /** Проверяет доступность конкретного источника для очереди. */
    fun TrackInstance.isPlayable(): Boolean = when (this) {
        is TrackInstance.Vk -> {
            if (!vkUris.containsKey(id)) vkUris[id] = resolveVkUri(this)
            vkUris[id]?.isNotBlank() == true
        }
        is TrackInstance.Local -> true
        is TrackInstance.Yandex -> track.available || isYandexCached(track.id)
    }

    val selected = instances.firstOrNull { instance ->
        instance.id == preferredInstanceId && instance.isPlayable()
    } ?: localInstances.firstOrNull()
        ?: yandexInstances.firstOrNull { instance ->
            instance.track.available || isYandexCached(instance.track.id)
        }
        ?: instances.filterIsInstance<TrackInstance.Vk>().firstOrNull { it.isPlayable() }
        ?: return null

    return when (selected) {
        is TrackInstance.Vk -> PlaybackTrack(
            id = selected.track.fullId, songId = id, instanceId = selected.id,
            source = MusicSource.VK, title = title, artistNames = artistNames,
            durationMs = durationMs, playbackUri = vkUris[selected.id] ?: return null, artworkUri = coverUri,
        )
        is TrackInstance.Yandex -> PlaybackTrack(
            id = selected.track.id,
            songId = id,
            instanceId = selected.id,
            source = MusicSource.YANDEX,
            title = title,
            artistNames = artistNames,
            durationMs = durationMs,
            playbackUri = "ya://${selected.track.id}",
            artworkUri = coverUri,
            yandexTrack = selected.track,
        )
        is TrackInstance.Local -> PlaybackTrack(
            id = selected.track.instanceId,
            songId = id,
            instanceId = selected.id,
            source = MusicSource.LOCAL,
            title = title,
            artistNames = artistNames,
            durationMs = durationMs,
            playbackUri = selected.track.contentUri,
            artworkUri = coverUri,
            localTrack = selected.track,
        )
    }
}
