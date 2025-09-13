package com.yellastrodev.dwij.models

import android.os.Bundle
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.yellastrodev.dwij.TRACK_ID
import com.yellastrodev.dwij.adapters.TrackListAdapter
import com.yellastrodev.dwij.data.repo.CoverRepository
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.data.repo.PlaylistRepository
import com.yellastrodev.dwij.data.repo.TrackRepository
import com.yellastrodev.dwij.entities.dPlaylistTrack
import com.yellastrodev.dwij.entities.dYaPlaylist
import com.yellastrodev.dwij.entities.dYaTrack
import com.yellastrodev.yandexmusiclib.entities.CoverSize
import com.yellastrodev.yandexmusiclib.entities.TrackShort
import com.yellastrodev.yandexmusiclib.entities.YaPlaylist
import com.yellastrodev.yandexmusiclib.entities.YaTrack
import com.yellastrodev.yandexmusiclib.kot_utils.yTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch


class TracklistModel(
    private val repo: PlaylistRepository,
    val coverRepo: CoverRepository,
    private val trackRepo: TrackRepository,
    private val playerRepo: PlayerRepository
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
        private val playerRepo: PlayerRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(TracklistModel::class.java)) {
                Log.d(TAG, "Создаём экземпляр TracklistModel через Factory")
                return TracklistModel(repo, coverRepo, trackRepo, playerRepo) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    private val _openPlayerScreen = MutableStateFlow<Boolean>(false)
    val openPlayerScreen: StateFlow<Boolean> = _openPlayerScreen


    /** Текущее состояние плейлиста (null, пока не загружен). */
    private val _playlist = MutableStateFlow<dYaPlaylist?>(null)
    val playlist: StateFlow<dYaPlaylist?> = _playlist

    /** Адаптер списка треков с ленивой инициализацией. */
    val adapter: TrackListAdapter by lazy {
        Log.d(TAG, "Инициализация адаптера треков")
        TrackListAdapter({ track ->
            Log.d(TAG, "Загрузка обложки для трека: ${track.id}")
            coverRepo.getCover(track, CoverSize.`100x100`)
        }, { pos ->
            onTrackClicked(playlist.value!!.tracks, pos)
        }).apply {
            mScope = viewModelScope
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
            repo.playlistFlow(value)
                .onEach { playlist ->
                    Log.d(TAG, "Получен плейлист: ${playlist.playlistUuid}, треков=${playlist.tracks.size}")
                    _playlist.value = playlist
                }
                .flatMapLatest { playlist ->
                    trackRepo.tracksFlow(playlist.tracks)
                }
                .onEach { tracks ->
                    Log.d(TAG, "Треки после collect: ${tracks.size}")
                    adapter.setList(ArrayList(tracks))
                }
                .launchIn(viewModelScope) // подписка живёт пока жив ViewModel
        } else {
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
        Log.d(TAG, "Обновляем плейлист: ${current.playlistUuid}")
        repo.refreshPlaylist(current.playlistUuid)
    }

    suspend fun onTrackClicked(tracks: List<dPlaylistTrack>, index: Int) {
        // уведомляем UI, что надо открыть плеер
        _openPlayerScreen.value = true

        // что бы не тормозить смену экрана, иначе валью будет ждать этого почемуто
        // нихуя не изменилось
        viewModelScope.launch {
            val curentTrackList: List<dYaTrack?> = tracks.map { trackSh ->
                    trackRepo.tracks.value[trackSh.trackId]?.let { track ->
                        return@map track

                    }
                return@map null
            }
            playerRepo.playQueue(curentTrackList as List<dYaTrack>, index)
        }
    }

    fun resetOpenPlayerScreen() {
        _openPlayerScreen.value = false
    }
}