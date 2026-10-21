package com.yellastrodev.dwij.playback

import com.yellastrodev.dwij.data.entities.PlaybackTrack
import com.yellastrodev.dwij.data.entities.dTracklist
import com.yellastrodev.dwij.utils.PlayerEvent
import com.yellastrodev.dwij.utils.PlayerState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Платформенный плеер с согласованным перехватом окончательного отказа перед auto-next. */
interface PlayerEngine {

    /** True означает, что общая логика забрала переход; callback не должен вызывать команды движка. */
    fun setFailureHandler(handler: (index: Int, networkFailure: Boolean) -> Boolean) {}

    /** Меняет окружение текущего элемента без перезапуска его аудио; false при устаревшем индексе. */
    suspend fun replaceQueueKeepingCurrent(tracks: List<PlaybackTrack>, oldIndex: Int, newIndex: Int,
                                         tracklist: dTracklist?, stillCurrent: () -> Boolean): Boolean = false

    /** Останавливает намерение играть; clearQueue удаляет недоступную очередь из системного плеера. */
    suspend fun stopPlayback(clearQueue: Boolean = false) {}

    val state: StateFlow<PlayerState>

    val events: SharedFlow<PlayerEvent>

    suspend fun prepare()

    /** Устанавливает очередь; восстановление с autoPlay=false не снимает пользовательскую паузу. */
    suspend fun setQueue(
        tracks: List<PlaybackTrack>,
        startIndex: Int,
        tracklist: dTracklist?,
        autoPlay: Boolean = true,
    )

    suspend fun appendTracks(
        tracks: List<PlaybackTrack>,
        tracklist: dTracklist?,
    )

    /** Возвращает индексы ближайших треков в фактическом порядке плеера. */
    suspend fun getUpcomingIndices(limit: Int): List<Int>

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
