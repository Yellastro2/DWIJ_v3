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

class AndroidMediaItemMapper {

    fun map(
        track: PlaybackTrack,
        tracklist: dTracklist?,
    ): MediaItem {
        val extras = Bundle().apply {
            putString(TRACK_ID, track.id)

            putString(
                PlaybackMetadataKeys.MUSIC_SOURCE,
                if (track.source == MusicSource.LOCAL) {
                    PlaybackMetadataKeys.SOURCE_LOCAL
                } else {
                    PlaybackMetadataKeys.SOURCE_YANDEX
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

                track.durationMs?.let { durationMs ->
                    putLong(
                        PlaybackMetadataKeys.PLAY_DURATION_MS,
                        durationMs,
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
                if (track.source == MusicSource.LOCAL) {
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

    private companion object {
        const val ANDROID_PLAYBACK_REPORT_SOURCE = "dwij-android"
    }
}
