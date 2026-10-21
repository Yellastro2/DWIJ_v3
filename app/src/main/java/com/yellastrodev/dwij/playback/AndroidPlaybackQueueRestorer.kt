package com.yellastrodev.dwij.playback

import androidx.media3.common.MediaItem
import com.yellastrodev.dwij.data.entities.Artist
import com.yellastrodev.dwij.data.entities.MusicSource
import com.yellastrodev.dwij.data.entities.PlaybackTrack
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.TrackInstance
import com.yellastrodev.dwij.data.repo.SongRepository
import com.yellastrodev.dwij.playback.feedback.PlaybackMetadataKeys

/** Собирает UI-очередь из фактических MediaItem, не меняя источник, URI, порядок и повторы. */
class AndroidPlaybackQueueRestorer(private val songs: SongRepository) {

    /** Читает только локальную БД; отсутствующие записи заменяет сохранённой метадатой. */
    suspend fun restore(items: List<MediaItem>): List<Pair<Song, PlaybackTrack>> {
        val indexed = items.groupBy(::source).flatMap { (source, group) ->
            songs.songsForSourceIds(source, group.map { it.mediaId }.distinct())
                .flatMap { song ->
                    song.instances.map { instance -> instance.id to song }
                }
        }.toMap()

        return items.map { item ->
            val source = source(item)
            val sourceInstanceId = when (source) {
                MusicSource.YANDEX -> "yandex:${item.mediaId}"
                MusicSource.VK -> "vk:${item.mediaId}"
                MusicSource.LOCAL -> item.mediaId
            }
            val metadata = item.mediaMetadata
            val indexedSong = indexed[sourceInstanceId]
            val instanceId = if (indexedSong != null) sourceInstanceId else
                metadata.extras?.getString(AndroidMediaItemMapper.INSTANCE_ID) ?: sourceInstanceId
            val duration = metadata.extras?.getLong(PlaybackMetadataKeys.PLAY_DURATION_MS)
                ?.takeIf { it > 0L }
            val song = indexedSong ?: Song(
                id = metadata.extras?.getString(AndroidMediaItemMapper.SONG_ID) ?: instanceId,
                title = metadata.title?.toString().orEmpty(),
                artists = listOf(Artist("", metadata.artist?.toString().orEmpty())),
                albums = emptyList(),
                durationMs = duration,
                coverUri = metadata.artworkUri?.toString(),
                instances = emptyList(),
                preferredInstanceId = null,
                hasPendingMatchCandidate = false,
                isLocalOnlyInLibrary = source == MusicSource.LOCAL,
                isLiked = false,
            )
            val instance = song.instances.firstOrNull { it.id == instanceId }
            song to PlaybackTrack(
                id = item.mediaId,
                songId = song.id,
                instanceId = instanceId,
                source = source,
                title = song.title,
                artistNames = song.artistNames,
                durationMs = song.durationMs ?: duration,
                playbackUri = requireNotNull(item.localConfiguration).uri.toString(),
                artworkUri = song.coverUri,
                yandexTrack = (instance as? TrackInstance.Yandex)?.track,
                localTrack = (instance as? TrackInstance.Local)?.track,
            )
        }
    }

    /** Читает источник старых снимков; URI служит запасным признаком при отсутствии extras. */
    private fun source(item: MediaItem): MusicSource =
        when (item.mediaMetadata.extras?.getString(PlaybackMetadataKeys.MUSIC_SOURCE)) {
            PlaybackMetadataKeys.SOURCE_LOCAL -> MusicSource.LOCAL
            PlaybackMetadataKeys.SOURCE_VK -> MusicSource.VK
            PlaybackMetadataKeys.SOURCE_YANDEX -> MusicSource.YANDEX
            else -> if (item.localConfiguration?.uri?.scheme == "ya") MusicSource.YANDEX else MusicSource.LOCAL
        }
}
