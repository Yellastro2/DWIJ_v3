package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.entities.PlaybackTrack
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.dTracklist
import com.yellastrodev.dwij.utils.PlayerEvent
import com.yellastrodev.dwij.utils.PlayerState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface PlaybackQueue {

    val isShuffleBlock: StateFlow<Boolean>
    val state: StateFlow<PlayerState>
    val currentPlaybackTrack: StateFlow<PlaybackTrack?>
//    val currentItem: StateFlow<PlaybackTrack?>
//    val currentTracklist: StateFlow<dTracklist?>
    val events: SharedFlow<PlayerEvent>

    suspend fun playQueue(
        songs: List<Song>,
        startIndex: Int = 0,
        tracklist: dTracklist,
    )

    suspend fun addTracks(songs: List<Song>)
}