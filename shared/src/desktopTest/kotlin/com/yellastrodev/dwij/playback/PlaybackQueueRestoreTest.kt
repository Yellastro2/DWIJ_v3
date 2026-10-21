package com.yellastrodev.dwij.playback

import com.yellastrodev.dwij.data.entities.PlaybackTrack
import com.yellastrodev.dwij.data.entities.dTracklist
import com.yellastrodev.dwij.data.entities.toPlaybackTrack
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.utils.PlayerState
import com.yellastrodev.dwij.utils.PlayerEvent
import com.yellastrodev.yamusicsdk.NoOpYamLogger
import com.yellastrodev.yamusicsdk.entities.YaTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Проверяет восстановление UI без команд плееру и защиту от запоздавшего снимка. */
class PlaybackQueueRestoreTest {
    /** Повторы сохраняют индексы; последующие события движка выбирают песню из восстановленной очереди. */
    @Test
    fun restoresPlayingQueueAndFollowsTrackChanges() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val engine = ReadOnlyEngine()
            val repo = repository(engine, scope)
            val first = song("1")
            val second = song("2")
            val songs = listOf(first, first, second)
            val tracks = songs.map { requireNotNull(it.toPlaybackTrack { false }) }
            val state = PlayerState(isPlaying = true, wantsToPlay = true, currentIndex = 1,
                currentPosition = 42000, duration = 120000, isShuffle = true, isRepeatAll = true)
            engine.state.value = state

            assertTrue(repo.restoreQueueIfEmpty(songs, tracks, state))
            assertEquals(songs.map { it.id }, repo.currentTrackList)
            assertEquals(first, repo.currentSong.value)
            assertEquals(tracks[1], repo.currentPlaybackTrack.value)
            assertEquals(state, repo.state.value)
            assertEquals(1, repo.relativeIndex)

            engine.state.value = state.copy(currentIndex = 2)
            assertEquals(second, repo.currentSong.value)
            assertEquals(tracks[2], repo.currentPlaybackTrack.value)
        } finally {
            scope.cancel()
        }
    }

    /** Некорректный снимок не заполняет репозиторий, а поздний снимок не заменяет принятую очередь. */
    @Test
    fun rejectsInvalidAndStaleRestoration() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repo = repository(ReadOnlyEngine(), scope)
            val current = song("1")
            val track = requireNotNull(current.toPlaybackTrack { false })
            assertFalse(repo.restoreQueueIfEmpty(listOf(current), emptyList(), PlayerState()))
            assertFalse(repo.restoreQueueIfEmpty(listOf(current), listOf(track), PlayerState(currentIndex = 1)))
            assertTrue(repo.restoreQueueIfEmpty(listOf(current), listOf(track), PlayerState()))

            val stale = song("2")
            assertFalse(repo.restoreQueueIfEmpty(listOf(stale),
                listOf(requireNotNull(stale.toPlaybackTrack { false })), PlayerState()))
            assertEquals(current, repo.currentSong.value)
            assertEquals(listOf(current.id), repo.currentTrackList)
        } finally {
            scope.cancel()
        }
    }

    /** Создаёт репозиторий без сетевых и файловых действий. */
    private fun repository(engine: PlayerEngine, scope: CoroutineScope) = PlayerRepository(
        engine = engine,
        settings = object : PlaybackSettings {
            override var shuffleEnabled = false
            override var repeatMode = RepeatMode.OFF
        },
        scope = scope,
        isTrackCached = { false },
        prefetchTrack = {},
        continueWave = {},
        logger = NoOpYamLogger,
    )

    /** Создаёт временную песню вне фонотеки с доступным Яндекс-экземпляром. */
    private fun song(id: String) = YaTrack(id, "Трек $id", available = true,
        durationMs = 120000, artists = emptyList(), albums = emptyList()).toDailyPlaylistSong()

    /** Любая команда движку проваливает тест: восстановление должно только принять его состояние. */
    private class ReadOnlyEngine : PlayerEngine {
        override val state = MutableStateFlow(PlayerState())
        override val events = MutableSharedFlow<PlayerEvent>()

        /** Запрещает повторную подготовку при восстановлении UI. */
        override suspend fun prepare(): Unit = error("Неожиданная prepare")
        /** Запрещает замену уже играющей очереди. */
        override suspend fun setQueue(tracks: List<PlaybackTrack>, startIndex: Int, tracklist: dTracklist?, autoPlay: Boolean): Unit = error("Неожиданная setQueue")
        /** Запрещает добавление треков при восстановлении UI. */
        override suspend fun appendTracks(tracks: List<PlaybackTrack>, tracklist: dTracklist?): Unit = error("Неожиданная appendTracks")
        /** Запрещает запрос порядка плеера при принятии снимка. */
        override suspend fun getUpcomingIndices(limit: Int): List<Int> = error("Неожиданная getUpcomingIndices")
        /** Запрещает повторный запуск трека. */
        override suspend fun playTrack(index: Int): Unit = error("Неожиданная playTrack")
        /** Запрещает переключение воспроизведения. */
        override suspend fun togglePlayPause(): Unit = error("Неожиданная togglePlayPause")
        /** Запрещает переход вперёд. */
        override suspend fun skipNext(): Unit = error("Неожиданная skipNext")
        /** Запрещает переход назад. */
        override suspend fun skipPrevious(): Unit = error("Неожиданная skipPrevious")
        /** Запрещает сброс позиции. */
        override suspend fun seekTo(positionMs: Long): Unit = error("Неожиданная seekTo")
        /** Запрещает замену режима перемешивания. */
        override suspend fun setShuffleEnabled(enabled: Boolean): Unit = error("Неожиданная setShuffleEnabled")
        /** Запрещает замену режима повтора. */
        override suspend fun setRepeatMode(mode: RepeatMode): Unit = error("Неожиданная setRepeatMode")
    }
}
