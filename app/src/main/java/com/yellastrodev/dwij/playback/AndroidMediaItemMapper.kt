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
import com.yellastrodev.dwij.service.DEFAULT_PLAY_AUDIO_SOURCE
import com.yellastrodev.dwij.service.PLAYBACK_MUSIC_SOURCE
import com.yellastrodev.dwij.service.PLAYBACK_SOURCE_LOCAL
import com.yellastrodev.dwij.service.PLAYBACK_SOURCE_YANDEX
import com.yellastrodev.dwij.service.PLAY_AUDIO_ALBUM_ID
import com.yellastrodev.dwij.service.PLAY_AUDIO_DURATION_MS
import com.yellastrodev.dwij.service.PLAY_AUDIO_ITEM_ID
import com.yellastrodev.dwij.service.PLAY_AUDIO_PLAYLIST_ID
import com.yellastrodev.dwij.service.PLAY_AUDIO_SOURCE
import java.util.UUID

class AndroidMediaItemMapper {

    fun map(
        track: PlaybackTrack,
        tracklist: dTracklist?,
    ): MediaItem {
        val extras = Bundle().apply {
            putString(TRACK_ID, track.id)

            putString(
                PLAYBACK_MUSIC_SOURCE,
                if (track.source == MusicSource.LOCAL) {
                    PLAYBACK_SOURCE_LOCAL
                } else {
                    PLAYBACK_SOURCE_YANDEX
                },
            )

            if (track.source == MusicSource.YANDEX) {
                putString(
                    PLAY_AUDIO_ITEM_ID,
                    UUID.randomUUID().toString(),
                )

                track.yandexTrack
                    ?.albums
                    ?.firstOrNull()
                    ?.id
                    ?.let { albumId ->
                        putString(
                            PLAY_AUDIO_ALBUM_ID,
                            albumId.toString(),
                        )
                    }

                track.durationMs?.let { durationMs ->
                    putLong(
                        PLAY_AUDIO_DURATION_MS,
                        durationMs,
                    )
                }

                (tracklist as? dYaPlaylist)
                    ?.playlistUuid
                    ?.let { playlistUuid ->
                        putString(
                            PLAY_AUDIO_PLAYLIST_ID,
                            playlistUuid,
                        )
                    }

                putString(
                    PLAY_AUDIO_SOURCE,
                    DEFAULT_PLAY_AUDIO_SOURCE,
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
}