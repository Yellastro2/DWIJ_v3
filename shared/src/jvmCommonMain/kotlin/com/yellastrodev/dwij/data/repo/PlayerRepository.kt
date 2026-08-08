package com.yellastrodev.dwij.data.repo


import com.yellastrodev.dwij.data.entities.LocalTracklist
import com.yellastrodev.dwij.data.entities.PlaybackTrack
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.dTracklist
import com.yellastrodev.dwij.data.entities.dYaWave
import com.yellastrodev.dwij.data.entities.toPlaybackTrack
import com.yellastrodev.dwij.playback.PlaybackSettings
import com.yellastrodev.dwij.playback.PlayerEngine
import com.yellastrodev.dwij.playback.RepeatMode
import com.yellastrodev.dwij.utils.PlayerEvent
import com.yellastrodev.dwij.utils.PlayerState
import com.yellastrodev.yamusicsdk.YamLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerRepository(
    private val engine: PlayerEngine,
    private val settings: PlaybackSettings,
    private val scope: CoroutineScope,
    private val isTrackCached: (trackId: String) -> Boolean,
    private val continueWave: suspend (dTracklist) -> Unit,
    private val logger: YamLogger
) : PlaybackQueue {

    private val mutableState = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = mutableState.asStateFlow()

    private val mutableTracklist = MutableStateFlow<dTracklist?>(null)
    val dtracklist: StateFlow<dTracklist?> = mutableTracklist.asStateFlow()

    var currentTrackList: List<String> = emptyList()
        private set

    private var currentSongQueue: List<Song> = emptyList()
    private var currentPlaybackQueue: List<PlaybackTrack> = emptyList()

    private val mutableCurrentTrack = MutableStateFlow<String?>(null)
    val currentTrack: StateFlow<String?> = mutableCurrentTrack.asStateFlow()

    private val mutableCurrentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = mutableCurrentSong.asStateFlow()

    private val mutableCurrentPlaybackTrack =
        MutableStateFlow<PlaybackTrack?>(null)

    override val currentPlaybackTrack: StateFlow<PlaybackTrack?> =
        mutableCurrentPlaybackTrack.asStateFlow()

    private val mutableEvents = MutableSharedFlow<PlayerEvent>()
    override val events: SharedFlow<PlayerEvent> = mutableEvents.asSharedFlow()

    private val mutableIsShuffleBlock = MutableStateFlow(false)
    override val isShuffleBlock: StateFlow<Boolean> =
        mutableIsShuffleBlock.asStateFlow()

    var relativeIndex: Int = 0
        private set

    init {
        engine.state
            .onEach { playerState ->
                if (playerState.currentIndex != mutableState.value.currentIndex) {
                    updateCurrentTrack(playerState.currentIndex)
                }

                mutableState.value = playerState
            }
            .launchIn(scope)

        engine.events
            .onEach(::handleEngineEvent)
            .launchIn(scope)
    }

    private suspend fun handleEngineEvent(event: PlayerEvent) {
        if (event is PlayerEvent.TrackListEnd) {
            val tracklist = dtracklist.value

            if (
                tracklist != null &&
                tracklist.getType() != LocalTracklist.Companion.TYPE
            ) {
                continueWave(tracklist)
                return
            }
        }

        mutableEvents.emit(event)
    }

    /**
     * Запускает очередь логических песен, выбирая для каждой
     * воспроизводимый экземпляр.
     *
     * [startIndex] переводится из индекса исходного списка
     * в индекс уже отфильтрованной воспроизводимой очереди.
     */
    override suspend fun playQueue(
        songs: List<Song>,
        startIndex: Int,
        tracklist: dTracklist,
    ) {
        if (songs.isEmpty() || startIndex !in songs.indices) {
            logger.warning(
                TAG,
                "[playQueue] Некорректная исходная очередь: " +
                        "size=${songs.size}, startIndex=$startIndex",
            )
            return
        }

        val playableSongs = withContext(Dispatchers.IO) {
            songs.mapIndexedNotNull { index, song ->
                song.toPlaybackTrack(isTrackCached)?.let { playbackTrack ->
                    IndexedValue(
                        index = index,
                        value = song to playbackTrack,
                    )
                }
            }
        }

        if (playableSongs.isEmpty()) {
            logger.warning(
                TAG,
                "[playQueue] В очереди нет воспроизводимых экземпляров",
            )
            return
        }

        val resolvedStartIndex = playableSongs
            .indexOfFirst { indexedSong ->
                indexedSong.index == startIndex
            }
            .takeIf { index -> index >= 0 }
            ?: playableSongs
                .indexOfFirst { indexedSong ->
                    indexedSong.index > startIndex
                }
                .takeIf { index -> index >= 0 }
            ?: 0

        val skippedCount = songs.size - playableSongs.size

        if (skippedCount > 0) {
            logger.debug(
                TAG,
                "[playQueue] Пропущено песен без воспроизводимых " +
                        "экземпляров=$skippedCount, исходный index=$startIndex, " +
                        "итоговый index=$resolvedStartIndex",
            )
        }

        playPreparedQueue(
            songs = playableSongs.map { indexedSong ->
                indexedSong.value.first
            },
            tracks = playableSongs.map { indexedSong ->
                indexedSong.value.second
            },
            startIndex = resolvedStartIndex,
            tracklist = tracklist,
        )
    }

    private suspend fun playPreparedQueue(
        songs: List<Song>,
        tracks: List<PlaybackTrack>,
        startIndex: Int,
        tracklist: dTracklist,
    ) {
        if (tracks.isEmpty() || startIndex !in tracks.indices) {
            logger.warning(
                TAG,
                "[playQueue] Некорректная подготовленная очередь: " +
                        "size=${tracks.size}, startIndex=$startIndex",
            )
            return
        }

        logger.debug(TAG, "[playQueue] Подготовка очереди")

        engine.prepare()

        if (dtracklist.value?.getdId() == tracklist.getdId()) {
            logger.debug(TAG, "[playQueue] Треклист не изменился")

            val newSongIds = songs.map(Song::id)

            val sameInstances =
                currentPlaybackQueue.map(PlaybackTrack::instanceId) ==
                        tracks.map(PlaybackTrack::instanceId)

            if (
                currentTrackList == newSongIds &&
                sameInstances
            ) {
                if (state.value.currentIndex == startIndex) {
                    logger.debug(
                        TAG,
                        "[playQueue] Выбранная позиция очереди уже играет",
                    )
                    return
                }

                mutableCurrentTrack.value = songs[startIndex].id
                mutableCurrentSong.value = songs[startIndex]
                mutableCurrentPlaybackTrack.value =
                    currentPlaybackQueue.getOrNull(startIndex)

                relativeIndex = startIndex

                engine.playTrack(startIndex)

                logger.debug(
                    TAG,
                    "[playQueue] Переключаем текущую очередь на " +
                            "index=$startIndex, songId=${songs[startIndex].id}",
                )
                return
            }

            logger.debug(
                TAG,
                "[playQueue] Состав текущего треклиста изменился",
            )
        }

        blockShuffle(
            isWave = tracklist.getType() == dYaWave.Companion.YA_WAVE,
        )

        currentTrackList = songs.map(Song::id)
        currentSongQueue = songs
        currentPlaybackQueue = tracks

        mutableCurrentTrack.value = songs[startIndex].id
        mutableCurrentSong.value = songs[startIndex]
        mutableCurrentPlaybackTrack.value = tracks[startIndex]
        mutableTracklist.value = tracklist

        relativeIndex = startIndex

        logger.debug(
            TAG,
            "[playQueue] Формируем очередь: size=${tracks.size}, " +
                    "startIndex=$startIndex, songId=${songs[startIndex].id}, " +
                    "instanceId=${tracks[startIndex].instanceId}",
        )

        engine.setQueue(
            tracks = tracks,
            startIndex = startIndex,
            tracklist = tracklist,
        )

        logger.debug(
            TAG,
            "[playQueue] Очередь передана в engine",
        )
    }

    private suspend fun blockShuffle(
        isWave: Boolean,
    ) {
        mutableIsShuffleBlock.value = isWave

        if (isWave) {
            engine.setShuffleEnabled(false)
            engine.setRepeatMode(RepeatMode.OFF)
        } else {
            applySavedModes()
        }
    }

    /**
     * Догружает в текущую очередь песни,
     * для которых удалось выбрать воспроизводимый экземпляр.
     */
    override suspend fun addTracks(
        songs: List<Song>,
    ) {
        val resolved = withContext(Dispatchers.IO) {
            songs.mapNotNull { song ->
                song.toPlaybackTrack(isTrackCached)?.let { playbackTrack ->
                    song to playbackTrack
                }
            }
        }

        val skippedCount = songs.size - resolved.size

        logger.debug(
            TAG,
            "[addTracks] Получено=${songs.size}, " +
                    "добавляем=${resolved.size}, пропущено=$skippedCount",
        )

        if (resolved.isEmpty()) {
            return
        }

        val resolvedSongs = resolved.map { pair ->
            pair.first
        }

        val playbackTracks = resolved.map { pair ->
            pair.second
        }

        currentTrackList =
            currentTrackList + resolvedSongs.map(Song::id)

        currentSongQueue =
            currentSongQueue + resolvedSongs

        currentPlaybackQueue =
            currentPlaybackQueue + playbackTracks

        engine.appendTracks(
            tracks = playbackTracks,
            tracklist = dtracklist.value,
        )
    }

    fun pause() {
        scope.launch {
            engine.togglePlayPause()
        }
    }

    suspend fun skipNext() {
        engine.skipNext()
    }

    suspend fun skipPrev() {
        engine.skipPrevious()
    }

    fun seekTo(
        positionMs: Long,
    ) {
        scope.launch {
            engine.seekTo(positionMs)
        }
    }

    fun shuffle() {
        if (isShuffleBlock.value) {
            return
        }

        val newValue = !state.value.isShuffle

        settings.shuffleEnabled = newValue

        scope.launch {
            engine.setShuffleEnabled(newValue)
        }
    }

    fun rotate() {
        if (isShuffleBlock.value) {
            return
        }

        val newMode = if (state.value.isRepeatAll) {
            RepeatMode.OFF
        } else {
            RepeatMode.ALL
        }

        settings.repeatMode = newMode

        scope.launch {
            engine.setRepeatMode(newMode)
        }
    }

    private suspend fun applySavedModes() {
        engine.setShuffleEnabled(
            settings.shuffleEnabled,
        )

        engine.setRepeatMode(
            settings.repeatMode,
        )
    }

    fun List<Song>.stableHash(): Int {
        return fold(1) { accumulator, song ->
            31 * accumulator + song.id.hashCode()
        }
    }

    private fun updateCurrentTrack(
        index: Int,
    ) {
        val song = currentSongQueue.getOrNull(index)
        val playbackTrack = currentPlaybackQueue.getOrNull(index)

        mutableCurrentTrack.value = song?.id
        mutableCurrentSong.value = song
        mutableCurrentPlaybackTrack.value = playbackTrack
    }

    /**
     * Заменяет объединённые Song в уже играющей очереди,
     * не перезапуская платформенный плеер.
     */
    fun applyMergedSong(
        sourceSongIds: Set<String>,
        mergedSong: Song,
    ) {
        if (sourceSongIds.isEmpty()) {
            return
        }

        currentSongQueue = currentSongQueue.map { song ->
            if (song.id in sourceSongIds) {
                mergedSong
            } else {
                song
            }
        }

        currentTrackList = currentTrackList.map { songId ->
            if (songId in sourceSongIds) {
                mergedSong.id
            } else {
                songId
            }
        }

        val currentSongId = currentSong.value?.id

        if (currentSongId != null && currentSongId in sourceSongIds) {
            mutableCurrentSong.value = mergedSong
            mutableCurrentTrack.value = mergedSong.id
        }
    }

    private companion object {
        const val TAG = "PlayerRepository"
    }
}