package com.yellastrodev.dwij.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.yellastrodev.dwij.TRACK_ID
import com.yellastrodev.dwij.data.entities.MusicSource
import com.yellastrodev.dwij.data.entities.PlaybackTrack
import com.yellastrodev.dwij.data.entities.dTracklist
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.playback.feedback.PlaybackMetadataKeys
import java.util.UUID

/** Передаёт источник, идентичность песни и URI в Media3 для воспроизведения и восстановления UI. */
class AndroidMediaItemMapper {

    /** Сохраняет ID логической песни и экземпляра, включая временные песни вне Room. */
    fun map(
        track: PlaybackTrack,
        tracklist: dTracklist?,
    ): MediaItem {
        val extras = Bundle().apply {
            putString(TRACK_ID, track.id)
            putString(SONG_ID, track.songId)
            putString(INSTANCE_ID, track.instanceId)
            track.durationMs?.let { putLong(PlaybackMetadataKeys.PLAY_DURATION_MS, it) }

            putString(
                PlaybackMetadataKeys.MUSIC_SOURCE,
                when (track.source) {
                    MusicSource.LOCAL -> PlaybackMetadataKeys.SOURCE_LOCAL
                    MusicSource.VK -> PlaybackMetadataKeys.SOURCE_VK
                    MusicSource.YANDEX -> PlaybackMetadataKeys.SOURCE_YANDEX
                },
            )

            if (track.source == MusicSource.YANDEX) {
                putString(
                    PlaybackMetadataKeys.PLAY_ITEM_ID,
                    UUID.randomUUID().toString(),
                )

                track.yandexTrack
                    ?.albums
                    ?.firstOrNull()
                    ?.id
                    ?.let { albumId ->
                        putString(
                            PlaybackMetadataKeys.PLAY_ALBUM_ID,
                            albumId.toString(),
                        )
                    }

                track.yandexTrack?.available?.let { available ->
                    putBoolean(
                        PlaybackMetadataKeys.YANDEX_AVAILABLE,
                        available,
                    )
                }

                (tracklist as? dYaPlaylist)
                    ?.playlistUuid
                    ?.let { playlistUuid ->
                        putString(
                            PlaybackMetadataKeys.PLAY_PLAYLIST_ID,
                            playlistUuid,
                        )
                    }

                putString(
                    PlaybackMetadataKeys.PLAY_SOURCE,
                    ANDROID_PLAYBACK_REPORT_SOURCE,
                )
            }
        }

        val metadata = MediaMetadata.Builder()
            .setExtras(extras)
            .setTitle(track.title)
            .setArtist(track.artistNames.joinToString(", "))
            .apply {
                if (track.source != MusicSource.YANDEX) {
                    track.artworkUri?.let { artworkUri ->
                        setArtworkUri(Uri.parse(artworkUri))
                    }
                }
            }
            .build()

        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(track.playbackUri)
            .setMediaMetadata(metadata)
            .build()
    }

    companion object {
        const val SONG_ID = "dwij_song_id"
        const val INSTANCE_ID = "dwij_instance_id"
        private const val ANDROID_PLAYBACK_REPORT_SOURCE = "dwij-android"
    }
}
