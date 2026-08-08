package com.yellastrodev.dwij.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.dSimpleTracklist
import com.yellastrodev.dwij.data.entities.dTracklist
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.repo.CoverData
import com.yellastrodev.dwij.data.repo.CoverRepository
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.data.repo.PlaylistRepository
import com.yellastrodev.dwij.data.repo.SongRepository
import com.yellastrodev.dwij.data.repo.TrackCacheRepository
import com.yellastrodev.dwij.data.repo.TrackRepository
import com.yellastrodev.dwij.data.repo.WaveRepository
import com.yellastrodev.yamusicsdk.YamLogger
import com.yellastrodev.yamusicsdk.entities.CoverSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.reflect.KClass

/**
 * Готовит треки выбранного Яндекс-объекта, проверяет кэш недоступных записей
 * и управляет запуском его очереди.
 */
class TracklistModel(
    private val playlistRepo: PlaylistRepository,
    private val coverRepo: CoverRepository,
    private val trackRepo: TrackRepository,
    private val trackCacheRepo: TrackCacheRepository,
    private val songRepo: SongRepository,
    private val playerRepo: PlayerRepository,
    private val waveRepository: WaveRepository,
    private val logger: YamLogger,
) : ViewModel() {

    /** Factory для создания [TracklistModel] с передачей зависимостей. */
    class Factory(
        private val repo: PlaylistRepository,
        private val coverRepo: CoverRepository,
        private val trackRepo: TrackRepository,
        private val trackCacheRepo: TrackCacheRepository,
        private val songRepo: SongRepository,
        private val playerRepo: PlayerRepository,
        private val waveRepo: WaveRepository,
        private val logger: YamLogger,
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: KClass<T>,
            extras: CreationExtras,
        ): T {
            if (modelClass == TracklistModel::class) {
                logger.debug(TAG, "Создаём экземпляр TracklistModel через Factory")

                return TracklistModel(
                    playlistRepo = repo,
                    coverRepo = coverRepo,
                    trackRepo = trackRepo,
                    trackCacheRepo = trackCacheRepo,
                    songRepo = songRepo,
                    playerRepo = playerRepo,
                    waveRepository = waveRepo,
                    logger = logger,
                ) as T
            }

            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    private var trackList: List<Song> = emptyList()
    private var tracksJob: Job? = null
    private var listIdentity: String? = null

    private val _tracks = MutableStateFlow<List<Song>>(emptyList())
    val tracks: StateFlow<List<Song>> = _tracks

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _cachedUnavailableSongIds = MutableStateFlow<Set<String>>(emptySet())

    /** Песни с недоступным Яндекс-инстансом, который всё ещё есть в файловом кэше. */
    val cachedUnavailableSongIds: StateFlow<Set<String>> = _cachedUnavailableSongIds

    private val scrollResetChannel = Channel<Unit>(Channel.CONFLATED)
    val scrollResetEvents = scrollResetChannel.receiveAsFlow()

    /** Текущее состояние плейлиста (null, пока не загружен). */
    private val _playlist = MutableStateFlow<dTracklist?>(null)
    val playlist: StateFlow<dTracklist?> = _playlist

    /**
     * Устанавливает тип и значение объекта и публикует его треки для Compose-списка.
     *
     * @param type тип объекта: playlist или общий tracklist
     * @param value идентификатор объекта
     */
    fun setType(type: String, value: String) {
        val newIdentity = "$type:$value"

        if (listIdentity == newIdentity && tracksJob?.isActive == true) {
            return
        }

        logger.debug(TAG, "[setType] type=$type, value=$value")

        tracksJob?.cancel()
        listIdentity = newIdentity
        trackList = emptyList()
        _tracks.value = emptyList()
        _isLoading.value = true
        _cachedUnavailableSongIds.value = emptySet()
        _playlist.value = null

        var resetScrollOnFirstList = true
        val unavailableCacheChecks = mutableMapOf<String, Boolean>()

        suspend fun publishTracks(sourceTracks: List<dYaTrack>) {
            val snapshot = songRepo.songsForYandexTracks(sourceTracks)
            val unavailableIds = sourceTracks
                .asSequence()
                .filterNot(dYaTrack::available)
                .map(dYaTrack::id)
                .toSet()
            val uncheckedIds = unavailableIds - unavailableCacheChecks.keys

            if (uncheckedIds.isNotEmpty()) {
                unavailableCacheChecks.putAll(
                    withContext(Dispatchers.IO) {
                        uncheckedIds.associateWith(trackCacheRepo::isCached)
                    },
                )
            }

            _cachedUnavailableSongIds.value = snapshot
                .filter { song ->
                    song.yandexInstances.any { instance ->
                        !instance.track.available &&
                                unavailableCacheChecks[instance.track.id] == true
                    }
                }
                .mapTo(mutableSetOf(), Song::id)

            trackList = snapshot
            _tracks.value = snapshot
            _isLoading.value = false

            if (resetScrollOnFirstList) {
                scrollResetChannel.trySend(Unit)
                resetScrollOnFirstList = false
            }
        }

        when (type) {
            OBJECT_TYPE_PLAYLIST -> {
                tracksJob = playlistRepo.playlistFlow(value)
                    .onEach { playlist ->
                        logger.debug(
                            TAG,
                            "[setType] Получен плейлист ${playlist.playlistUuid}, " +
                                    "треков=${playlist.tracks.size}",
                        )
                        _playlist.value = playlist
                    }
                    .flatMapLatest { playlist ->
                        trackRepo.tracksFlow(playlist.tracks)
                    }
                    .onEach { tracks ->
                        logger.debug(TAG, "[setType] Получено треков=${tracks.size}")
                        publishTracks(tracks)
                    }
                    .catch { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error

                        logger.error(
                            TAG,
                            "[setType] Не удалось загрузить треки плейлиста",
                            error,
                        )
                        _isLoading.value = false
                    }
                    .launchIn(viewModelScope)
            }

            OBJECT_TYPE_TRACKLIST -> {
                _playlist.value = dSimpleTracklist()

                val allTracksFlow: Flow<List<dYaTrack>> =
                    playlistRepo.playlists.flatMapLatest { playlistList ->
                        if (playlistList.isEmpty()) {
                            flowOf(emptyList())
                        } else {
                            val flows = playlistList.map { playlist ->
                                trackRepo.tracksFlow(playlist.tracks)
                            }

                            merge(*flows.toTypedArray())
                                .scan(emptyMap<String, dYaTrack>()) { acc, newList ->
                                    acc + newList.associateBy { it.id }
                                }
                                .map { it.values.toList() }
                        }
                    }.distinctUntilChanged()

                tracksJob = allTracksFlow
                    .onEach { tracks -> publishTracks(tracks) }
                    .catch { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error

                        logger.error(
                            TAG,
                            "[setType] Не удалось загрузить общий список треков",
                            error,
                        )
                        _isLoading.value = false
                    }
                    .launchIn(viewModelScope)
            }

            else -> {
                logger.warning(TAG, "Неизвестный тип: $type")
                _isLoading.value = false
            }
        }
    }

    /** Принудительно обновляет текущий плейлист с сервера. */
    suspend fun refreshObject() {
        val current = requireNotNull(_playlist.value) {
            "Невозможно обновить: плейлист не загружен"
        }

        val yandexPlaylist = current as? dYaPlaylist

        if (yandexPlaylist != null) {
            logger.debug(TAG, "Обновляем плейлист: ${yandexPlaylist.playlistUuid}")
            playlistRepo.refreshPlaylist(yandexPlaylist.playlistUuid)
        } else {
            logger.warning(TAG, "Неизвестный тип плейлиста: ${current.getType()}")
        }
    }

    fun onTrackClicked(index: Int, expectedSongId: String? = null): Boolean {
        val queue = trackList.toList()
        val resolvedIndex = when {
            index in queue.indices &&
                    (expectedSongId == null || queue[index].id == expectedSongId) -> index

            expectedSongId != null -> queue.indexOfFirst { it.id == expectedSongId }
            else -> -1
        }

        if (resolvedIndex !in queue.indices) {
            logger.warning(
                TAG,
                "[onTrackClicked] Песня не найдена: index=$index, songId=$expectedSongId",
            )
            return false
        }

        val selectedTracklist = playlist.value ?: run {
            logger.warning(TAG, "[onTrackClicked] Треклист ещё не загружен")
            return false
        }

        logger.debug(
            TAG,
            "[onTrackClicked] requestedIndex=$index, resolvedIndex=$resolvedIndex, " +
                    "songId=${queue[resolvedIndex].id}, queueSize=${queue.size}",
        )

        viewModelScope.launch {
            playerRepo.playQueue(
                songs = queue,
                startIndex = resolvedIndex,
                tracklist = selectedTracklist,
            )
        }

        return true
    }

    /** Обновляет снимок списка после объединения source-инстансов. */
    fun applyMergedSong(sourceSongIds: Set<String>, mergedSong: Song) {
        if (sourceSongIds.isEmpty()) return

        trackList = trackList.map { song ->
            if (song.id in sourceSongIds) mergedSong else song
        }
        _tracks.value = trackList
    }

    /** Возвращает данные небольшой обложки трека без платформенного декодирования. */
    suspend fun getTrackCover(songId: String): CoverData? {
        val track = trackList.firstOrNull { it.id == songId }
            ?.yandexInstances
            ?.firstOrNull()
            ?.track
            ?: return null

        return withContext(Dispatchers.IO) {
            coverRepo.getTrackCover(
                track = track,
                size = CoverSize.`100x100`,
            )
        }
    }

    /** Возвращает данные обложки плейлиста без платформенного декодирования. */
    suspend fun getPlaylistCover(playlist: dYaPlaylist): CoverData =
        withContext(Dispatchers.IO) {
            requireNotNull(
                coverRepo.getPlaylistCover(
                    playlist = playlist,
                    size = CoverSize.`200x200`,
                ),
            ) {
                "Не удалось получить обложку плейлиста ${playlist.playlistUuid}"
            }
        }

    /** Запускает волну объекта в application-scope. */
    fun requestWave(): Boolean = waveRepository.requestWave(_playlist.value)

    override fun onCleared() {
        tracksJob?.cancel()
        scrollResetChannel.close()
        super.onCleared()
    }

    private companion object {
        const val TAG = "TracklistModel"
        const val OBJECT_TYPE_PLAYLIST = "playlist"
        const val OBJECT_TYPE_TRACKLIST = "tracklist"
    }
}