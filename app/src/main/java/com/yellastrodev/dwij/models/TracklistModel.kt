package com.yellastrodev.dwij.models

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yellastrodev.dwij.adapters.TrackListAdapter
import com.yellastrodev.dwij.data.entities.dSimpleTracklist
import com.yellastrodev.dwij.data.entities.dTracklist
import com.yellastrodev.dwij.data.repo.CoverRepository
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.data.repo.PlaylistRepository
import com.yellastrodev.dwij.data.repo.TrackRepository
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.repo.WaveRepository
import com.yellastrodev.dwij.fragments.ObjectFrag.Companion.TRACKLIST
import com.yellastrodev.yandexmusiclib.entities.CoverSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch


class TracklistModel(
    private val playlistRepo: PlaylistRepository,
    val coverRepo: CoverRepository,
    private val trackRepo: TrackRepository,
    private val playerRepo: PlayerRepository,
    private val waveRepository: WaveRepository
) : ViewModel() {

    companion object {
        private const val TAG = "TracklistModel"
    }

    /**
     * Factory для создания [TracklistModel] с передачей зависимостей.
     */
    class Factory(
        private val repo: PlaylistRepository,
        private val coverRepo: CoverRepository,
        private val trackRepo: TrackRepository,
        private val playerRepo: PlayerRepository,
        private val waveRepo: WaveRepository
    ) : ViewModelProvider.Factory
    {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(TracklistModel::class.java)) {
                Log.d(TAG, "Создаём экземпляр TracklistModel через Factory")
                return TracklistModel(repo, coverRepo, trackRepo, playerRepo, waveRepo) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    private var trackList: List<dYaTrack> = emptyList()
    private var tracksJob: Job? = null
    private var listIdentity: String? = null

    private val scrollResetChannel = Channel<Unit>(Channel.CONFLATED)
    val scrollResetEvents = scrollResetChannel.receiveAsFlow()


    /** Текущее состояние плейлиста (null, пока не загружен). */
    private val _playlist = MutableStateFlow<dTracklist?>(null)
    val playlist: StateFlow<dTracklist?> = _playlist

    /** Адаптер списка треков с ленивой инициализацией. */
    val adapter: TrackListAdapter by lazy {
        Log.d(TAG, "Инициализация адаптера треков | Model id=${this.hashCode()}")
        TrackListAdapter({ track ->
            Log.d(TAG, "Загрузка обложки для трека: ${track.id}")
            coverRepo.getCover(track, CoverSize.`100x100`)
        }).apply {
            mScope = viewModelScope
            Log.d(TAG, "Адаптер инициализирован | Adapter id=${this.hashCode()}")
        }
    }

    /**
     * Устанавливает тип и значение объекта для отображения.
     * Сейчас поддерживается только тип "playlist".
     *
     * @param type тип объекта (например, "playlist")
     * @param value идентификатор объекта
     */
    fun setType(type: String, value: String) {
        val newIdentity = "$type:$value"
        if (listIdentity == newIdentity && tracksJob?.isActive == true) {
            return
        }
        Log.d(TAG, "[setType] type=$type, value=$value")
        tracksJob?.cancel()
        listIdentity = newIdentity
        trackList = emptyList()
        adapter.setList(emptyList())
        _playlist.value = null
        var resetScrollOnFirstList = true

        fun publishTracks(tracks: List<dYaTrack>) {
            val snapshot = tracks.toList()
            trackList = snapshot
            adapter.setList(snapshot)
            if (resetScrollOnFirstList) {
                scrollResetChannel.trySend(Unit)
                resetScrollOnFirstList = false
            }
        }

        if (type == "playlist") {
            tracksJob = playlistRepo.playlistFlow(value)
                .onEach { playlist ->
                    Log.d(
                        TAG,
                        "[setType] Получен плейлист ${playlist.playlistUuid}, " +
                            "треков=${playlist.tracks.size}"
                    )
                    _playlist.value = playlist
                }
                .flatMapLatest { playlist ->
                    trackRepo.tracksFlow(playlist.tracks)
                }
                .onEach { tracks ->
                    Log.d(TAG, "[setType] Получено треков=${tracks.size}")
                    publishTracks(tracks)
                }
                .launchIn(viewModelScope)
        } else if (type == TRACKLIST) {
            _playlist.value = dSimpleTracklist()
            val allTracksFlow: Flow<List<dYaTrack>> =
                playlistRepo.playlists.flatMapLatest { playlistList ->
                    if (playlistList.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        val flows = playlistList.map { playlist ->
                            trackRepo.tracksFlow(playlist.tracks)
                        }

                        // Сливаем все потоки в один
                        merge(*flows.toTypedArray())
                            .scan(emptyMap<String, dYaTrack>()) { acc, newList ->
                                // обновляем кэш по id
                                acc + newList.associateBy { it.id }
                            }
                            .map { it.values.toList() }
                    }
                }
                    .distinctUntilChanged()
            tracksJob = allTracksFlow
                .onEach { tracks -> publishTracks(tracks) }
                .launchIn(viewModelScope)
        } else
        {
            Log.w(TAG, "Неизвестный тип: $type")
        }
    }

    /**
     * Принудительно обновляет текущий плейлист с сервера.
     * Бросит исключение, если плейлист ещё не загружен.
     */
    suspend fun refreshObject() {
        val current = _playlist.value
        requireNotNull(current) { "Невозможно обновить: плейлист не загружен" }
        if (current.getType() == dYaPlaylist.YA_PLAYLIST) {
            (current as dYaPlaylist)
            Log.d(TAG, "Обновляем плейлист: ${current.playlistUuid}")
            playlistRepo.refreshPlaylist(current.playlistUuid)
        }
        else {
            Log.w(TAG, "Неизвестный тип плейлиста: ${current.getType()}")
        }
    }

    fun onTrackClicked(index: Int, expectedTrackId: String? = null): Boolean {
        val queue = trackList.toList()
        val resolvedIndex = when {
            index in queue.indices &&
                (expectedTrackId == null || queue[index].id == expectedTrackId) -> index
            expectedTrackId != null -> queue.indexOfFirst { it.id == expectedTrackId }
            else -> -1
        }
        if (resolvedIndex !in queue.indices) {
            Log.w(
                TAG,
                "[onTrackClicked] Трек не найден: index=$index, trackId=$expectedTrackId"
            )
            return false
        }
        val selectedTracklist = playlist.value ?: run {
            Log.w(TAG, "[onTrackClicked] Треклист ещё не загружен")
            return false
        }
        Log.d(
            TAG,
            "[onTrackClicked] requestedIndex=$index, resolvedIndex=$resolvedIndex, " +
                "trackId=${queue[resolvedIndex].id}, queueSize=${queue.size}"
        )
        viewModelScope.launch {
            playerRepo.playQueue(
                tracks = queue,
                startIndex = resolvedIndex,
                tracklist = selectedTracklist
            )
        }
        return true
    }


    suspend fun playWave() {
        waveRepository.playWave(_playlist.value)
    }

    override fun onCleared() {
        tracksJob?.cancel()
        scrollResetChannel.close()
        super.onCleared()
    }
}
