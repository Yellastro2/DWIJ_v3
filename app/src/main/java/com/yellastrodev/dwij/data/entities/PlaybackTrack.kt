package com.yellastrodev.dwij.data.entities

/** Источник конкретного воспроизводимого экземпляра трека. */
enum class MusicSource {
    YANDEX,
    LOCAL,
}

/**
 * Общая граница между каталогами и плеером.
 * Позже несколько таких экземпляров можно объединить одной сущностью MusicTrack.
 */
data class PlaybackTrack(
    val id: String,
    val source: MusicSource,
    val title: String,
    val artistNames: List<String>,
    val durationMs: Long?,
    val playbackUri: String,
    val artworkUri: String?,
    val yandexTrack: dYaTrack? = null,
    val localTrack: LocalTrackEntity? = null,
)

fun dYaTrack.toPlaybackTrack(): PlaybackTrack = PlaybackTrack(
    id = id,
    source = MusicSource.YANDEX,
    title = title,
    artistNames = artists.map { it.name },
    durationMs = durationMs?.toLong(),
    playbackUri = "ya://$id",
    artworkUri = null,
    yandexTrack = this,
)

fun LocalTrackEntity.toPlaybackTrack(): PlaybackTrack = PlaybackTrack(
    id = instanceId,
    source = MusicSource.LOCAL,
    title = title,
    artistNames = artist?.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty(),
    durationMs = durationMs,
    playbackUri = contentUri,
    artworkUri = albumId?.let { "content://media/external/audio/albumart/$it" },
    localTrack = this,
)
