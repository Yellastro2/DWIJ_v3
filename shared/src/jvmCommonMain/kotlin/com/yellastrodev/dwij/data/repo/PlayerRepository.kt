package com.yellastrodev.dwij.data.repo


import com.yellastrodev.dwij.data.entities.LocalTracklist
import com.yellastrodev.dwij.data.entities.MusicSource
import com.yellastrodev.dwij.data.entities.PlaybackTrack
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.dTracklist
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.entities.dYaWave
import com.yellastrodev.dwij.data.entities.toPlaybackTrack
import com.yellastrodev.dwij.playback.PlaybackSettings
import com.yellastrodev.dwij.playback.PlayerEngine
import com.yellastrodev.dwij.playback.RepeatMode
import com.yellastrodev.dwij.utils.PlayerEvent
import com.yellastrodev.dwij.utils.PlayerState
import com.yellastrodev.yamusicsdk.YamLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.yellastrodev.dwij.playback.OfflinePlaybackPolicy

/** Управляет очередью, автоматическим офлайном и восстановлением полного состава без перезапуска текущего звука. */
class PlayerRepository(
    private val engine: PlayerEngine,
    private val settings: PlaybackSettings,
    private val scope: CoroutineScope,
    private val isTrackCached: (trackId: String) -> Boolean,
    private val prefetchTrack: suspend (trackId: String) -> Unit,
    private val continueWave: suspend (dTracklist) -> Unit,
    private val logger: YamLogger,
    private val resolveVkUri: (com.yellastrodev.dwij.data.entities.TrackInstance.Vk) -> String? = { null },
    private val resolveOfflineTrack: (Song) -> PlaybackTrack? = { null },
    private val networkFailure: (PlaybackTrack, Boolean) -> Boolean = { track, network -> track.source != MusicSource.LOCAL && network },
    private val probeOnline: suspend (List<Song>) -> Boolean = { false },
) : PlaybackQueue {

    private val offlinePolicy = OfflinePlaybackPolicy()
    val isOffline: StateFlow<Boolean> = offlinePolicy.offline
    @Volatile private var originalSongs: List<Song> = emptyList()
    @Volatile private var offlinePositions: List<Int> = emptyList()
    private val rejectedOfflinePositions = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    @Volatile private var userQueueGeneration = 0L
    private val queueCommandMutex = Mutex()
    @Volatile private var offlineTransition: Job? = null
    @Volatile private var probeJob: Job? = null
    private var lastProbeMs = 0L

    private val mutableState = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = mutableState.asStateFlow()

    private val mutableTracklist = MutableStateFlow<dTracklist?>(null)
    val dtracklist: StateFlow<dTracklist?> = mutableTracklist.asStateFlow()

    var currentTrackList: List<String> = emptyList()
        private set

    @Volatile private var currentSongQueue: List<Song> = emptyList()
    @Volatile private var currentPlaybackQueue: List<PlaybackTrack> = emptyList()
    @Volatile private var queueRevision = 0L
    private val queueRestoreLock = Any()

    private var prefetchJob: Job? = null
    private var lastPrefetchKey: String? = null

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
        engine.setFailureHandler(::onTerminalFailure)
        engine.state
            .onEach { playerState ->
                offlinePolicy.progress(playerState)
                if (playerState.currentIndex != mutableState.value.currentIndex) {
                    updateCurrentTrack(playerState.currentIndex)
                    scheduleOnlineProbe()
                }

                mutableState.value = playerState
                schedulePrefetch(playerState)
            }
            .launchIn(scope)

        engine.events
            .onEach(::handleEngineEvent)
            .launchIn(scope)
    }

    /** Продолжает ЯМ-волну только онлайн и с явно указанным поддерживаемым seed треклиста. */
    private suspend fun handleEngineEvent(event: PlayerEvent) {
        if (event is PlayerEvent.TrackListEnd) {
            val tracklist = dtracklist.value

            if (
                !isOffline.value && tracklist != null &&
                !tracklist.yandexWaveSeed().isNullOrBlank()
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
     * VK callback разрешает лишь локальный адрес relay; сетевые запросы остаются ленивыми.
     * В офлайне сохраняет исходный состав отдельно и выбирает только готовые локальные экземпляры.
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

        userQueueGeneration++
        val generation = userQueueGeneration
        offlineTransition?.cancel()
        probeJob?.cancel()
        originalSongs = songs
        offlinePositions = emptyList()
        rejectedOfflinePositions.clear()
        offlinePolicy.resetStreak()

        val playableSongs = withContext(Dispatchers.IO) {
            songs.mapIndexedNotNull { index, song ->
                resolveSong(song)?.let { playbackTrack ->
                    IndexedValue(
                        index = index,
                        value = song to playbackTrack,
                    )
                }
            }
        }
        if (generation != userQueueGeneration) return

        if (playableSongs.isEmpty()) {
            if (isOffline.value) {
                mutableTracklist.value = tracklist
                clearUnavailableQueue(generation)
                mutableEvents.emit(PlayerEvent.ShowError("В этой очереди нет треков, доступных без сети"))
                scheduleOnlineProbe()
            }
            logger.warning(
                TAG,
                "[playQueue] В очереди нет воспроизводимых экземпляров",
            )
            return
        }

        offlinePositions = if (isOffline.value) playableSongs.map { it.index } else emptyList()

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
            expectedGeneration = generation,
        )
        scheduleOnlineProbe()
    }

    /** Публикует очередь, включая восстановленную без контекста; autoPlay=false сохраняет паузу. */
    private suspend fun playPreparedQueue(
        songs: List<Song>,
        tracks: List<PlaybackTrack>,
        startIndex: Int,
        tracklist: dTracklist?,
        autoPlay: Boolean = true,
        expectedGeneration: Long = userQueueGeneration,
    ): Unit = queueCommandMutex.withLock {
        if (expectedGeneration != userQueueGeneration) return
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
        if (expectedGeneration != userQueueGeneration) return

        if (tracklist != null && dtracklist.value?.getdId() == tracklist.getdId()) {
            logger.debug(TAG, "[playQueue] Треклист не изменился")

            val newSongIds = songs.map(Song::id)

            val sameInstances =
                currentPlaybackQueue.map { it.instanceId to it.playbackUri } ==
                        tracks.map { it.instanceId to it.playbackUri }

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
            isWave = tracklist?.getType() == dYaWave.Companion.YA_WAVE || tracklist?.preserveQueueOrder() == true,
        )

        synchronized(queueRestoreLock) {
            currentTrackList = songs.map(Song::id)
            currentSongQueue = songs
            currentPlaybackQueue = tracks
            queueRevision += 1L
            cancelPrefetch()

            mutableCurrentTrack.value = songs[startIndex].id
            mutableCurrentSong.value = songs[startIndex]
            mutableCurrentPlaybackTrack.value = tracks[startIndex]
            mutableTracklist.value = tracklist

            relativeIndex = startIndex
        }

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
            autoPlay = autoPlay,
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
     * Адреса VK создаются тем же callback, что и при запуске полной очереди.
     */
    override suspend fun addTracks(
        songs: List<Song>,
    ): Unit = queueCommandMutex.withLock {
        if (isOffline.value) {
            // Волна и другая автоматическая догрузка не расширяют офлайн-очередь сетевыми треками.
            return
        }
        val resolved = withContext(Dispatchers.IO) {
            songs.mapNotNull { song ->
                song.toPlaybackTrack(isTrackCached, resolveVkUri)?.let { playbackTrack ->
                    song to playbackTrack
                }
            }
        }
        if (isOffline.value) return

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
        originalSongs = originalSongs + resolvedSongs

        synchronized(queueRestoreLock) {
            currentTrackList = currentTrackList + resolvedSongs.map(Song::id)

            currentSongQueue = currentSongQueue + resolvedSongs

            currentPlaybackQueue = currentPlaybackQueue + playbackTracks

            queueRevision += 1L
        }

        engine.appendTracks(
            tracks = playbackTracks,
            tracklist = dtracklist.value,
        )

        schedulePrefetch(state.value)
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
     * Принимает фактическую очередь после смерти процесса, не вызывая команды движка.
     * Пустой контекст не выдумывает seed волны; новая пользовательская очередь имеет приоритет.
     */
    fun restoreQueueIfEmpty(
        songs: List<Song>,
        tracks: List<PlaybackTrack>,
        playerState: PlayerState,
    ): Boolean = synchronized(queueRestoreLock) {
        if (currentSongQueue.isNotEmpty() || songs.isEmpty() ||
            songs.size != tracks.size || playerState.currentIndex !in songs.indices
        ) return@synchronized false

        currentTrackList = songs.map(Song::id)
        currentSongQueue = songs
        currentPlaybackQueue = tracks
        originalSongs = songs
        queueRevision += 1L
        mutableTracklist.value = null
        mutableIsShuffleBlock.value = false
        relativeIndex = playerState.currentIndex
        mutableState.value = playerState
        updateCurrentTrack(playerState.currentIndex)
        logger.debug(TAG, "[restoreQueueIfEmpty] Очередь принята из плеера: size=${songs.size}, index=${playerState.currentIndex}")
        true
    }

    /**
     * Поддерживает в LRU-кэше два следующих ЯМ-трека плейлиста или волны.
     * Задание запускается только после подготовки текущего трека, чтобы фоновая
     * загрузка не заняла файловый mutex раньше воспроизведения.
     */
    private fun schedulePrefetch(
        playerState: PlayerState,
    ) {
        if (isOffline.value) {
            cancelPrefetch()
            return
        }
        val tracklist = dtracklist.value
        val isEligibleTracklist = when (tracklist?.getType()) {
            dYaPlaylist.YA_PLAYLIST,
            dYaWave.YA_WAVE -> true
            else -> false
        }

        if (
            !isEligibleTracklist ||
            playerState.pendingTrackChange != null
        ) {
            cancelPrefetch()
            return
        }

        val currentTrack =
            currentPlaybackQueue.getOrNull(playerState.currentIndex)
                ?: run {
                    cancelPrefetch()
                    return
                }

        val requestKey =
            "$queueRevision|${playerState.isShuffle}|${currentTrack.instanceId}"

        if (requestKey == lastPrefetchKey) {
            return
        }

        lastPrefetchKey = requestKey
        prefetchJob?.cancel()

        val queueSnapshot = currentPlaybackQueue

        prefetchJob = scope.launch(Dispatchers.IO) {
            val upcomingIndices = engine.getUpcomingIndices(
                PREFETCH_AHEAD_COUNT,
            )

            val trackIds = upcomingIndices
                .mapNotNull(queueSnapshot::getOrNull)
                .filter { track ->
                    track.source == MusicSource.YANDEX
                }
                .map(PlaybackTrack::id)
                .distinct()
                .filterNot(isTrackCached)

            trackIds.forEach { trackId ->
                try {
                    prefetchTrack(trackId)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    logger.error(
                        TAG,
                        "[schedulePrefetch] Не удалось предзагрузить трек $trackId",
                        error,
                    )
                }
            }
        }
    }

    private fun cancelPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
        lastPrefetchKey = null
    }

    /** Разрешение офлайн никогда не вызывает сетевой resolver и не использует неполный кеш. */
    private fun resolveSong(song: Song): PlaybackTrack? = if (isOffline.value) resolveOfflineTrack(song)
        else song.toPlaybackTrack(isTrackCached, resolveVkUri)

    /** Принимает решение синхронно; команды выполняются отдельной задачей после выхода из recovery движка. */
    private fun onTerminalFailure(index: Int, platformNetworkFailure: Boolean): Boolean {
        val track = currentPlaybackQueue.getOrNull(index) ?: return false
        val wasOffline = isOffline.value
        val network = networkFailure(track, platformNetworkFailure)
        if (!offlinePolicy.failed(queueRevision, index, network)) return false
        cancelPrefetch()
        val generation = userQueueGeneration
        val revision = queueRevision
        offlineTransition?.cancel()
        offlineTransition = scope.launch {
            yield()
            if (generation != userQueueGeneration || revision != queueRevision) return@launch
            val songs = if (offlinePositions.isEmpty()) currentSongQueue else originalSongs
            val failedOriginal = offlinePositions.getOrNull(index) ?: index
            if (wasOffline) rejectedOfflinePositions += failedOriginal
            val tracklist = dtracklist.value
            val upcoming = engine.getUpcomingIndices(currentSongQueue.size)
                .map { offlinePositions.getOrNull(it) ?: it }
            val local = withContext(Dispatchers.IO) {
                songs.mapIndexedNotNull { position, song ->
                    if (position in rejectedOfflinePositions) null
                    else resolveOfflineTrack(song)?.let { Triple(position, song, it) }
                }
            }
            if (generation != userQueueGeneration || revision != queueRevision) return@launch
            if (offlinePositions.isEmpty()) originalSongs = songs
            logger.warning(TAG, "[onTerminalFailure] Офлайн: доступно=${local.size}, отказавший индекс=$failedOriginal")
            if (local.isEmpty()) {
                clearUnavailableQueue(generation)
                mutableEvents.emit(PlayerEvent.ShowError("Офлайн: в очереди нет доступных локально треков"))
            } else {
                val preferred = (listOf(failedOriginal) + upcoming).firstNotNullOfOrNull { candidate ->
                    local.indexOfFirst { it.first == candidate }.takeIf { it >= 0 }
                } ?: 0
                offlinePositions = local.map { it.first }
                val wantsToPlay = engine.state.value.wantsToPlay
                playPreparedQueue(local.map { it.second }, local.map { it.third }, preferred, tracklist, wantsToPlay, generation)
            }
            scheduleOnlineProbe()
        }
        return true
    }

    /** Пустой офлайн-список не оставляет сетевую очередь доступной кнопке Play или гарнитуре. */
    private suspend fun clearUnavailableQueue(expectedGeneration: Long): Unit = queueCommandMutex.withLock {
        if (expectedGeneration != userQueueGeneration) return
        engine.stopPlayback(clearQueue = true)
        currentSongQueue = emptyList()
        currentPlaybackQueue = emptyList()
        currentTrackList = emptyList()
        mutableCurrentSong.value = null
        mutableCurrentTrack.value = null
        mutableCurrentPlaybackTrack.value = null
        offlinePositions = emptyList()
        queueRevision++
    }

    /** Один ограниченный probe между песнями; при пустой/законченной очереди продолжает проверять раз в 15с. */
    @Synchronized private fun scheduleOnlineProbe() {
        if (!isOffline.value || probeJob?.isActive == true) return
        val generation = userQueueGeneration
        probeJob = scope.launch {
            // Переход в офлайн сначала завершает замену очереди и текущие отмены транспорта.
            yield()
            offlineTransition?.join()
            while (isOffline.value && generation == userQueueGeneration) {
                val probeIndex = engine.state.value.currentIndex
                val elapsed = System.nanoTime() / 1_000_000 - lastProbeMs
                if (elapsed < 15_000) delay(15_000 - elapsed)
                lastProbeMs = System.nanoTime() / 1_000_000
                val online = try {
                    withTimeoutOrNull(8_000) { probeOnline(originalSongs) } == true
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    logger.debug(TAG, "[probeOnline] Онлайн ещё недоступен: ${error.javaClass.simpleName}")
                    false
                }
                if (online && generation == userQueueGeneration) {
                    restoreOnlineQueue(generation)
                    if (!isOffline.value) return@launch
                }
                delay(15_000)
                if (engine.state.value.isPlaying && engine.state.value.currentIndex == probeIndex) return@launch
            }
        }
    }

    /** Восстанавливает полный состав вокруг текущего файла, не перезапуская его воспроизведение. */
    private suspend fun restoreOnlineQueue(generation: Long) {
        val revision = queueRevision
        val resolved = withContext(Dispatchers.IO) {
            originalSongs.mapIndexedNotNull { index, song ->
                song.toPlaybackTrack(isTrackCached, resolveVkUri)?.let { Triple(index, song, it) }
            }
        }
        if (generation != userQueueGeneration || revision != queueRevision || resolved.isEmpty()) return
        val index = engine.state.value.currentIndex
        val originalIndex = offlinePositions.getOrNull(index) ?: index
        val restoredIndex = resolved.indexOfFirst { it.first == originalIndex }
        if (offlinePositions.isNotEmpty() && restoredIndex >= 0) {
            queueCommandMutex.withLock {
                if (generation != userQueueGeneration || revision != queueRevision) return
                val tracks = resolved.map { it.third }.toMutableList()
                currentPlaybackQueue.getOrNull(index)?.let { tracks[restoredIndex] = it }
                if (!engine.replaceQueueKeepingCurrent(tracks, index, restoredIndex, dtracklist.value) {
                        generation == userQueueGeneration && revision == queueRevision
                    }) return
                currentSongQueue = resolved.map { it.second }
                currentPlaybackQueue = tracks
                currentTrackList = currentSongQueue.map(Song::id)
                queueRevision++
                updateCurrentTrack(restoredIndex)
            }
        } else if (currentSongQueue.isEmpty()) {
            val tracklist = dtracklist.value
            offlinePolicy.onlineConfirmed()
            playPreparedQueue(resolved.map { it.second }, resolved.map { it.third },
                restoredIndex.coerceAtLeast(0), tracklist, autoPlay = false, expectedGeneration = generation)
        }
        if (generation != userQueueGeneration) return
        offlinePositions = emptyList()
        rejectedOfflinePositions.clear()
        offlinePolicy.onlineConfirmed()
        logger.info(TAG, "[restoreOnlineQueue] Сетевая проверка успешна, обычная очередь восстановлена")
        schedulePrefetch(engine.state.value)
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
        originalSongs = originalSongs.map { if (it.id in sourceSongIds) mergedSong else it }

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

    /** Обновляет данные существующей Song в очереди без смены позиции и перезапуска плеера. */
    fun applyUpdatedSong(updatedSong: Song) {
        originalSongs = originalSongs.map { if (it.id == updatedSong.id) updatedSong else it }
        currentSongQueue = currentSongQueue.map { song ->
            if (song.id == updatedSong.id) updatedSong else song
        }
        if (currentSong.value?.id == updatedSong.id) {
            mutableCurrentSong.value = updatedSong
        }
    }

    private companion object {
        const val TAG = "PlayerRepository"
        const val PREFETCH_AHEAD_COUNT = 2
    }
}
