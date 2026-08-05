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

/** Выбирает preferred, локальный либо доступный/закэшированный Яндекс-экземпляр. */
fun Song.toPlaybackTrack(isYandexCached: (String) -> Boolean): PlaybackTrack? {
    fun TrackInstance.isPlayable(): Boolean = when (this) {
        is TrackInstance.Local -> true
        is TrackInstance.Yandex -> track.available || isYandexCached(track.id)
    }

    val selected = instances.firstOrNull { instance ->
        instance.id == preferredInstanceId && instance.isPlayable()
    } ?: localInstances.firstOrNull()
        ?: yandexInstances.firstOrNull { instance ->
            instance.track.available || isYandexCached(instance.track.id)
        }
        ?: return null

    return when (selected) {
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
