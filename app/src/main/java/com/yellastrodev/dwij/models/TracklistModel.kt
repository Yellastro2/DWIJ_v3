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
import com.yellastrodev.dwij.data.repo.AlbumCoverRepository
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.data.repo.PlaylistRepository
import com.yellastrodev.dwij.data.repo.TrackRepository
import com.yellastrodev.yandexmusiclib.entities.CoverSize
import com.yellastrodev.yandexmusiclib.entities.TrackShort
import com.yellastrodev.yandexmusiclib.entities.YaPlaylist
import com.yellastrodev.yandexmusiclib.kot_utils.yTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.launchIn


class TracklistModel(
    private val repo: PlaylistRepository,
    val coverRepo: AlbumCoverRepository,
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
        private val coverRepo: AlbumCoverRepository,
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

    /** Текущее состояние плейлиста (null, пока не загружен). */
    private val _playlist = MutableStateFlow<YaPlaylist?>(null)
    val playlist: StateFlow<YaPlaylist?> = _playlist

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

    suspend fun onTrackClicked(tracks: List<TrackShort>, index: Int) {
        val mediaItems = tracks.map { trackSh ->
            trackRepo.tracks.value[trackSh.id]?. let { track ->
                val result = trackRepo.getTrack(track)
                if (result is yTrack.Companion.Mp3LinkResult.Success)
                    return@map MediaItem.Builder()
                        .setUri(result.url)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setExtras(Bundle().apply { putString(TRACK_ID, track.id) })
                                .setTitle(track.title ?: "Unknown title")
                                .setArtist(track.artists.joinToString(", ") { it.name } ?: "Unknown artist")
    //                            .setArtworkUri(Uri.parse("https://example.com/cover.jpg"))
                                .build()
                        )
                        .build()
            }
            null
        }
        playerRepo.playQueue(mediaItems as List<MediaItem>, index)
    }
}