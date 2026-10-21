package com.yellastrodev.dwij.playback

import com.yellastrodev.dwij.data.entities.LocalTracklist
import com.yellastrodev.dwij.data.entities.PlaybackTrack
import com.yellastrodev.dwij.data.entities.dTracklist
import com.yellastrodev.dwij.data.entities.toPlaybackTrack
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.utils.PlayerEvent
import com.yellastrodev.dwij.utils.PlayerState
import com.yellastrodev.yamusicsdk.NoOpYamLogger
import com.yellastrodev.yamusicsdk.entities.YaTrack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Проверяет границу движок/репозиторий: четвёртая сеть не запускается, текущий звук не перезапускается. */
class OfflinePlaybackQueueTest {
    /** После третьего отказа выбирается готовый файл; probe возвращает полный состав с прежней позицией. */
    @Test fun switchesBeforeFourthOnlineTrackAndRestoresWithoutRestart() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val engine = Engine()
            val confirmation = CompletableDeferred<Boolean>()
            val songs = (1..4).map { id -> YaTrack("$id", "Трек $id", available = true,
                durationMs = 120_000, artists = emptyList(), albums = emptyList()).toDailyPlaylistSong() }
            val repo = PlayerRepository(engine, Settings(), scope, { false }, {}, {}, NoOpYamLogger,
                resolveOfflineTrack = { song -> if (song.id == songs.last().id)
                    song.toPlaybackTrack { true }?.copy(playbackUri = "file:///offline.mp3") else null },
                probeOnline = { confirmation.await() })
            repo.playQueue(songs, 0, LocalTracklist("test", "Тест"))
            for (index in 0..2) {
                engine.state.value = engine.state.value.copy(currentIndex = index)
                assertEquals(index == 2, engine.failure(index, true))
            }
            withTimeout(3_000) { while (engine.tracks.size != 1 || engine.state.value.currentIndex != 0 || engine.starts != 2) delay(10) }
            assertTrue(repo.isOffline.value)
            assertEquals("file:///offline.mp3", engine.tracks.single().playbackUri)
            assertEquals(2, engine.starts)
            engine.state.value = engine.state.value.copy(isPlaying = true, currentPosition = 42_000)
            confirmation.complete(true)
            withTimeout(3_000) { while (repo.isOffline.value) delay(10) }
            assertEquals(songs.map { it.id }, repo.currentTrackList)
            assertEquals(42_000L, engine.state.value.currentPosition)
            assertEquals(3, engine.state.value.currentIndex)
            assertEquals(2, engine.starts)
            assertEquals(1, engine.replacements)
        } finally { scope.cancel() }
    }

    /** Без готовых файлов удаляются старые сетевые элементы, включая доступ к ним кнопкой Play. */
    @Test fun clearsUnplayableQueueInsteadOfLooping() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val engine = Engine()
            val repo = PlayerRepository(engine, Settings(), scope, { false }, {}, {}, NoOpYamLogger)
            val songs = (1..3).map { id -> YaTrack("$id", "Трек", available = true,
                durationMs = 120_000, artists = emptyList(), albums = emptyList()).toDailyPlaylistSong() }
            repo.playQueue(songs, 0, LocalTracklist("test", "Тест"))
            for (index in 0..2) {
                engine.state.value = engine.state.value.copy(currentIndex = index)
                engine.failure(index, true)
            }
            withTimeout(3_000) { while (engine.tracks.isNotEmpty()) delay(10) }
            assertTrue(repo.isOffline.value)
            assertFalse(engine.state.value.wantsToPlay)
            assertEquals(1, engine.starts)
        } finally { scope.cancel() }
    }

    /** Восстановленная сервисом очередь без исходного dTracklist тоже переключается в офлайн. */
    @Test fun handlesRestoredQueueWithoutTracklistContext() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val engine = Engine()
            val repo = PlayerRepository(engine, Settings(), scope, { false }, {}, {}, NoOpYamLogger)
            val songs = (1..3).map { id -> YaTrack("$id", "Трек", available = true,
                durationMs = 120_000, artists = emptyList(), albums = emptyList()).toDailyPlaylistSong() }
            val tracks = songs.map { requireNotNull(it.toPlaybackTrack { false }) }
            engine.setQueue(tracks, 0, null, true)
            assertTrue(repo.restoreQueueIfEmpty(songs, tracks, engine.state.value))
            for (index in 0..2) {
                engine.state.value = engine.state.value.copy(currentIndex = index)
                engine.failure(index, true)
            }
            withTimeout(3_000) { while (engine.tracks.isNotEmpty()) delay(10) }
            assertTrue(repo.isOffline.value)
        } finally { scope.cancel() }
    }

    /** Настройки не включают дополнительные сетевые сценарии. */
    private class Settings : PlaybackSettings {
        override var shuffleEnabled = false
        override var repeatMode = RepeatMode.OFF
    }

    /** Движок фиксирует команды, но никогда самостоятельно не читает аудио. */
    private class Engine : PlayerEngine {
        override val state = MutableStateFlow(PlayerState())
        override val events = MutableSharedFlow<PlayerEvent>()
        var tracks = emptyList<PlaybackTrack>()
        var starts = 0
        var replacements = 0
        var failure: (Int, Boolean) -> Boolean = { _, _ -> false }
        /** Сохраняет согласованный callback терминального отказа. */
        override fun setFailureHandler(handler: (Int, Boolean) -> Boolean) { failure = handler }
        /** Подготовка не воспроизводит звук. */
        override suspend fun prepare() {}
        /** Считает только реальные установки очереди. */
        override suspend fun setQueue(tracks: List<PlaybackTrack>, startIndex: Int, tracklist: dTracklist?, autoPlay: Boolean) {
            this.tracks = tracks
            starts++
            state.value = PlayerState(currentIndex = startIndex, wantsToPlay = autoPlay)
        }
        /** Подмена окружения сохраняет позицию и намерение воспроизведения. */
        override suspend fun replaceQueueKeepingCurrent(tracks: List<PlaybackTrack>, oldIndex: Int, newIndex: Int,
                                                      tracklist: dTracklist?, stillCurrent: () -> Boolean): Boolean {
            if (!stillCurrent() || state.value.currentIndex != oldIndex) return false
            this.tracks = tracks
            replacements++
            state.value = state.value.copy(currentIndex = newIndex)
            return true
        }
        /** Пустой офлайн очищает очередь и намерение играть. */
        override suspend fun stopPlayback(clearQueue: Boolean) {
            if (clearQueue) tracks = emptyList()
            state.value = state.value.copy(isPlaying = false, wantsToPlay = false)
        }
        /** Фиксирует добавленные элементы. */
        override suspend fun appendTracks(tracks: List<PlaybackTrack>, tracklist: dTracklist?) { this.tracks += tracks }
        /** Возвращает линейный порядок после отказавшего элемента. */
        override suspend fun getUpcomingIndices(limit: Int) = ((state.value.currentIndex + 1)..tracks.lastIndex).take(limit)
        /** Ручные команды не требуются этому сценарию. */
        override suspend fun playTrack(index: Int) {}
        /** Ручные команды не требуются этому сценарию. */
        override suspend fun togglePlayPause() {}
        /** Ручные команды не требуются этому сценарию. */
        override suspend fun skipNext() {}
        /** Ручные команды не требуются этому сценарию. */
        override suspend fun skipPrevious() {}
        /** Ручные команды не требуются этому сценарию. */
        override suspend fun seekTo(positionMs: Long) {}
        /** Настройка не запускает звук. */
        override suspend fun setShuffleEnabled(enabled: Boolean) {}
        /** Настройка не запускает звук. */
        override suspend fun setRepeatMode(mode: RepeatMode) {}
    }
}
