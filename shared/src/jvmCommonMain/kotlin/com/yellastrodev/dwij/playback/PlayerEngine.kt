package com.yellastrodev.dwij.playback

import com.yellastrodev.dwij.data.entities.PlaybackTrack
import com.yellastrodev.dwij.data.entities.dTracklist
import com.yellastrodev.dwij.utils.PlayerEvent
import com.yellastrodev.dwij.utils.PlayerState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface PlayerEngine {

    val state: StateFlow<PlayerState>

    val events: SharedFlow<PlayerEvent>

    suspend fun prepare()

    suspend fun setQueue(
        tracks: List<PlaybackTrack>,
        startIndex: Int,
        tracklist: dTracklist?,
    )

    suspend fun appendTracks(
        tracks: List<PlaybackTrack>,
        tracklist: dTracklist?,
    )

    suspend fun playTrack(index: Int)

    suspend fun togglePlayPause()

    suspend fun skipNext()

    suspend fun skipPrevious()

    suspend fun seekTo(positionMs: Long)

    suspend fun setShuffleEnabled(enabled: Boolean)

    suspend fun setRepeatMode(mode: RepeatMode)
}

enum class RepeatMode {
    OFF,
    ALL,
}

interface PlaybackSettings {

    var shuffleEnabled: Boolean

    var repeatMode: RepeatMode
}