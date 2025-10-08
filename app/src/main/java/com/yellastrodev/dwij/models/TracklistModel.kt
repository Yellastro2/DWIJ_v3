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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flattenMerge
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.collections.flatten


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

    var trackList = ArrayList<dYaTrack>()


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
    suspend fun setType(type: String, value: String) {
        Log.d(TAG, "setType: type=$type, value=$value")
        if (type == "playlist") {
            playlistRepo.playlistFlow(value)
                .onEach { playlist ->
                    Log.d(TAG, "Получен плейлист: ${playlist.playlistUuid}, треков=${playlist.tracks.size}")
                    _playlist.value = playlist
                }
                .flatMapLatest { playlist ->
                    trackRepo.tracksFlow(playlist.tracks)
                }
                .onEach { tracks ->
                    Log.d(TAG, "Треки после collect: ${tracks.size}")
                    trackList = ArrayList(tracks)
                    adapter.setList(trackList)
                }
                .launchIn(viewModelScope) // подписка живёт пока жив ViewModel
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
                            .distinctUntilChanged { old, new ->
                                // сравниваем по id-шникам, чтобы не триггерить лишние обновления
                                old.map { it.id } == new.map { it.id }
                            }
                    }
                }
                    .distinctUntilChanged()
            viewModelScope.launch  {
                allTracksFlow.collect { flowedTrackList ->
                    adapter.setList(flowedTrackList)
                    trackList = ArrayList(flowedTrackList)
                }
            }
//            playlistRepo.playlists
//                .flatMapLatest { playlists ->
//                    playlists
//                        .map { p -> trackRepo.tracksFlow(p.tracks) } // Flow<List<Track>>
//                        .asFlow()
//                        .flattenMerge() // emits as each inner flow updates
//                }
//                .onEach { tracks -> // List<Track>
//                    trackList.addAll(tracks)
//                    withContext(Dispatchers.Main) {
//                        adapter.setList(trackList) }
//                }
//                .launchIn(viewModelScope)
//            playlistRepo.playlists
//                .flatMapLatest { playlists ->
//                    combine(
//                        playlists.map { p -> trackRepo.tracksFlow(p.tracks) }
//                    ) { lists: Array<List<dYaTrack>> ->
//                        lists.toList().flatten().distinctBy { it.id } // убрали дубли
//                    }
//                }
//                .onEach { tracks ->
//                    adapter.setList(tracks)
//                }
//                .launchIn(viewModelScope)
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

    suspend fun onTrackClicked( index: Int) {

        // что бы не тормозить смену экрана, иначе валью будет ждать этого почемуто
        // нихуя не изменилось
        viewModelScope.launch {
            playerRepo.playQueue(
                trackList as List<dYaTrack>,
                index,
                playlist.value!!)
        }
    }


    suspend fun playWave() {
        waveRepository.playWave(_playlist.value)
    }
}