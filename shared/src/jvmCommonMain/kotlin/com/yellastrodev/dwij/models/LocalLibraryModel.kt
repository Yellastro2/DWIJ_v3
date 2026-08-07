package com.yellastrodev.dwij.models

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntity
import com.yellastrodev.dwij.data.entities.LocalTracklist
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.repo.LocalMusicRepository
import com.yellastrodev.dwij.data.repo.PlayerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/** Какое содержимое локальной медиатеки открыто текущим маршрутом. */
enum class LocalLibraryContent {
    PLAYLISTS,
    PLAYLIST,
    ALL_TRACKS,
}

/** Полный снимок данных локальной медиатеки для экрана. */
@Immutable
data class LocalLibraryUiState(
    val playlists: List<LocalPlaylistEntity>? = null,
    val playlist: LocalPlaylistEntity? = null,
    val tracks: List<Song>? = null,
    val isSynchronizing: Boolean = false,
    val pendingHideSong: Song? = null,
    val hideError: DataError? = null,
)

sealed interface LocalLibraryEvent {
    data object OpenPlayer : LocalLibraryEvent
}

/**
 * Общая логика локальной медиатеки.
 *
 * Не знает про Android Context, MediaStore, WorkManager, Toast и навигацию.
 */
class LocalLibraryModel(
    private val repository: LocalMusicRepository,
    private val playerRepository: PlayerRepository,
    private val content: LocalLibraryContent,
    private val playlistId: String?,
) : ViewModel() {

    private val _state = MutableStateFlow(LocalLibraryUiState())
    val state: StateFlow<LocalLibraryUiState> = _state.asStateFlow()

    private val eventChannel = Channel<LocalLibraryEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    init {
        repository.isSynchronizing
            .onEach { isSynchronizing ->
                _state.update { current ->
                    current.copy(isSynchronizing = isSynchronizing)
                }
            }
            .launchIn(viewModelScope)

        when (content) {
            LocalLibraryContent.PLAYLISTS -> observePlaylists()
            LocalLibraryContent.PLAYLIST -> observePlaylist()
            LocalLibraryContent.ALL_TRACKS -> observeAllTracks()
        }
    }

    private fun observePlaylists() {
        repository.playlists
            .onEach { playlists ->
                _state.update { current ->
                    current.copy(playlists = playlists)
                }
            }
            .launchIn(viewModelScope)
    }

    private fun observePlaylist() {
        val id = playlistId
        if (id == null) {
            _state.update { current ->
                current.copy(
                    playlist = null,
                    tracks = emptyList(),
                )
            }
            return
        }

        combine(
            repository.playlist(id),
            repository.playlistSongs(id),
        ) { playlist, tracks ->
            playlist to tracks
        }
            .onEach { (playlist, tracks) ->
                _state.update { current ->
                    current.copy(
                        playlist = playlist,
                        tracks = tracks,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    private fun observeAllTracks() {
        repository.songs
            .onEach { tracks ->
                _state.update { current ->
                    current.copy(tracks = tracks)
                }
            }
            .launchIn(viewModelScope)
    }

    fun play(
        index: Int,
        tracklistName: String,
    ) {
        val tracks = _state.value.tracks.orEmpty()
        if (index !in tracks.indices) return

        val tracklistId = when (content) {
            LocalLibraryContent.PLAYLIST -> playlistId ?: return
            LocalLibraryContent.ALL_TRACKS -> ALL_TRACKS_ID
            LocalLibraryContent.PLAYLISTS -> return
        }

        viewModelScope.launch {
            playerRepository.playQueue(
                songs = tracks,
                startIndex = index,
                tracklist = LocalTracklist(
                    id = tracklistId,
                    name = tracklistName,
                ),
            )

            eventChannel.send(LocalLibraryEvent.OpenPlayer)
        }
    }

    fun requestTrackHide(song: Song) {
        if (song.localInstances.isEmpty()) return

        _state.update { current ->
            current.copy(
                pendingHideSong = song,
                hideError = null,
            )
        }
    }

    fun dismissTrackHide() {
        _state.update { current ->
            current.copy(pendingHideSong = null)
        }
    }

    fun confirmTrackHide() {
        val song = _state.value.pendingHideSong ?: return
        val localInstanceIds = song.localInstances.map { instance -> instance.id }

        _state.update { current ->
            current.copy(
                pendingHideSong = null,
                hideError = null,
            )
        }

        if (localInstanceIds.isEmpty()) return

        viewModelScope.launch {
            val error = try {
                when (
                    val result = repository.setTracksHidden(
                        instanceIds = localInstanceIds,
                        isHidden = true,
                    )
                ) {
                    is DataResult.Success -> null
                    is DataResult.Failure -> result.error
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                DataError.Storage(error)
            }

            if (error != null) {
                _state.update { current ->
                    current.copy(hideError = error)
                }
            }
        }
    }

    fun consumeHideError() {
        _state.update { current ->
            current.copy(hideError = null)
        }
    }

    class Factory(
        private val repository: LocalMusicRepository,
        private val playerRepository: PlayerRepository,
        private val content: LocalLibraryContent,
        private val playlistId: String?,
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: KClass<T>,
            extras: CreationExtras,
        ): T {
            if (modelClass == LocalLibraryModel::class) {
                return LocalLibraryModel(
                    repository = repository,
                    playerRepository = playerRepository,
                    content = content,
                    playlistId = playlistId,
                ) as T
            }

            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    private companion object {
        const val ALL_TRACKS_ID = "local:all"
    }
}
