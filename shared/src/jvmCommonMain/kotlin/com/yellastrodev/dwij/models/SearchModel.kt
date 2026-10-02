package com.yellastrodev.dwij.models

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.yellastrodev.dwij.TrackListItemUiModel
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.dSimpleTracklist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.entities.toEntity
import com.yellastrodev.dwij.data.repo.LocalMusicRepository
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.data.repo.SearchRepository
import com.yellastrodev.dwij.data.repo.SongRepository
import com.yellastrodev.dwij.data.repo.TrackRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/** Вид сущности в общей поисковой выдаче. */
enum class SearchEntityKind {
    Album,
    Artist,
}

/** Конкретный источник поискового трека: локальная песня, Яндекс либо независимое VK-аудио. */
sealed interface SearchTrackSource {
    /** VK-аудио с составным owner_id/audio_id, независимое от Room-фонотеки. */
    data class Vk(val track: com.yellastrodev.vkmusicsdk.VkAudio) : SearchTrackSource
    data class Yandex(val track: dYaTrack) : SearchTrackSource
    data class Local(val song: Song) : SearchTrackSource
}

/** Один элемент смешанной поисковой выдачи. */
@Immutable
sealed interface SearchResultItemUiModel {
    val key: String
    val coverUri: String?

    data class Track(
        override val key: String,
        override val coverUri: String?,
        val row: TrackListItemUiModel,
        val source: SearchTrackSource,
    ) : SearchResultItemUiModel

    data class Entity(
        override val key: String,
        override val coverUri: String?,
        val externalId: Int,
        val title: String,
        val kind: SearchEntityKind,
        val artistNames: List<String> = emptyList(),
        val genres: List<String> = emptyList(),
        val trackCount: Int? = null,
        val likesCount: Long? = null,
    ) : SearchResultItemUiModel
}

/** Снимок поиска и подготовки VK-аудио, переживающий рекомпозиции экрана. */
@Immutable
data class SearchUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val results: List<SearchResultItemUiModel> = emptyList(),
    val error: DataError? = null,
    val isPreparingPlayback: Boolean = false,
)

/**
 * Управляет debounce, отменой устаревшего запроса и общей выдачей Яндекс, локальных и VK-треков.
 */
class SearchModel(
    private val repository: SearchRepository,
    private val localMusicRepository: LocalMusicRepository,
    private val trackRepository: TrackRepository,
    private val songRepository: SongRepository,
    private val playerRepository: PlayerRepository,
    private val onAuthorizationRequired: () -> Unit,
    private val vkRepository: com.yellastrodev.dwij.data.repo.VkMusicRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var selectedSource = com.yellastrodev.dwij.HomeMusicSource.Yandex
    private var playJob: Job? = null

    /** Обновляет введённый текст и заменяет ожидающий сетевой запрос новым. */
    fun updateQuery(query: String) {
        if (_state.value.query == query) return

        _state.value = _state.value.copy(query = query)
        scheduleSearch()
    }

    /** Смена источника отменяет прежний запрос и повторяет поиск в его хранилище. */
    fun setSource(source: com.yellastrodev.dwij.HomeMusicSource) {
        if (selectedSource == source) return

        selectedSource = source
        scheduleSearch()
    }

    /** Отменяет прежнюю выдачу и запускает поиск только в выбранном источнике. */
    private fun scheduleSearch() {
        searchJob?.cancel()

        val query = _state.value.query.trim()

        if (query.isBlank()) {
            _state.value = SearchUiState()
            return
        }

        _state.value = _state.value.copy(
            isLoading = true,
            hasSearched = false,
            results = emptyList(),
            error = null,
        )

        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)

            try {
                if (selectedSource == com.yellastrodev.dwij.HomeMusicSource.Vk) {
                    when (val result = vkRepository.search(query)) {
                        is DataResult.Success -> _state.value = _state.value.copy(
                            isLoading = false, hasSearched = true, error = null,
                            results = result.value.distinctBy { it.fullId }.map { audio ->
                                SearchResultItemUiModel.Track(
                                    key = "vk:${audio.fullId}", coverUri = audio.coverUrl,
                                    row = TrackListItemUiModel(
                                        key = "search:vk:${audio.fullId}", trackId = "vk:${audio.fullId}",
                                        title = audio.title, artist = audio.artistNames.joinToString(", "),
                                    ),
                                    source = SearchTrackSource.Vk(audio),
                                )
                            },
                        )
                        is DataResult.Failure -> _state.value = _state.value.copy(
                            isLoading = false, hasSearched = true, results = emptyList(), error = result.error,
                        )
                    }
                } else if (selectedSource == com.yellastrodev.dwij.HomeMusicSource.Yandex) {
                    when (val result = repository.searchAll(query)) {
                        is DataResult.Success -> {
                            _state.value = _state.value.copy(
                                isLoading = false,
                                hasSearched = true,
                                results = result.value.toUiItems(),
                                error = null,
                            )
                        }

                        is DataResult.Failure -> {
                            if (result.error == DataError.Unauthorized) {
                                onAuthorizationRequired()
                            }
                            _state.value = _state.value.copy(
                                isLoading = false,
                                hasSearched = true,
                                results = emptyList(),
                                error = result.error,
                            )
                        }
                    }
                } else {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        hasSearched = true,
                        results = localMusicRepository.searchSongs(query).toUiItems(),
                        error = null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            }
        }
    }

    private fun com.yellastrodev.yamusicsdk.search.SearchResponse.toUiItems():
            List<SearchResultItemUiModel> = buildList {
        tracks?.results.orEmpty().forEach { track ->
            add(
                SearchResultItemUiModel.Track(
                    key = "track:${track.id}",
                    coverUri = track.ogImageUri ?: track.coverUri,
                    row = TrackListItemUiModel(
                        key = "search:${track.id}",
                        trackId = track.id,
                        title = track.title,
                        artist = track.artists.joinToString(", ") { it.name },
                        isYandexUnavailable = !track.available,
                        isPlaybackBlocked = !track.available,
                    ),
                    source = SearchTrackSource.Yandex(track.toEntity()),
                ),
            )
        }

        albums?.results.orEmpty().forEach { album ->
            add(
                SearchResultItemUiModel.Entity(
                    key = "album:${album.id}",
                    coverUri = album.ogImageUri ?: album.coverUri,
                    externalId = album.id,
                    title = album.title,
                    kind = SearchEntityKind.Album,
                    artistNames = album.artists.map { it.name },
                    genres = listOfNotNull(album.genre),
                    trackCount = album.trackCount,
                    likesCount = album.likesCount,
                ),
            )
        }

        artists?.results.orEmpty().forEach { artist ->
            add(
                SearchResultItemUiModel.Entity(
                    key = "artist:${artist.id ?: artist.name}",
                    coverUri = artist.cover?.uri ?: artist.ogImageUri,
                    externalId = artist.id ?: return@forEach,
                    title = artist.name,
                    kind = SearchEntityKind.Artist,
                    genres = artist.genres.orEmpty(),
                    trackCount = artist.counts?.tracks,
                    likesCount = artist.likesCount,
                ),
            )
        }
    }

    private fun List<Song>.toUiItems(): List<SearchResultItemUiModel> = map { song ->
        SearchResultItemUiModel.Track(
            key = "local:${song.id}",
            coverUri = song.coverUri,
            row = TrackListItemUiModel(
                key = "search:local:${song.id}",
                trackId = song.id,
                title = song.title,
                artist = song.artistNames.joinToString(", "),
            ),
            source = SearchTrackSource.Local(song),
        )
    }

    /** Повторяет запрос после ошибки либо сохранения сессии без изменения текста поиска. */
    fun retrySearch() { scheduleSearch() }

    /** Запускает выбранный трек отдельной очередью; callback вызывается после передачи плееру. */
    fun playTrack(item: SearchResultItemUiModel.Track, onStarted: () -> Unit = {}) {
        if (playJob?.isActive == true) return
        playJob = viewModelScope.launch {
            val vkSource = item.source as? SearchTrackSource.Vk
            if (vkSource != null) {
                _state.value = _state.value.copy(isPreparingPlayback = true, error = null)
                try {
                    when (val result = vkRepository.play(vkSource.track, playerRepository)) {
                        is DataResult.Success -> onStarted()
                        is DataResult.Failure -> _state.value = _state.value.copy(error = result.error)
                    }
                } finally {
                    _state.value = _state.value.copy(isPreparingPlayback = false)
                }
                return@launch
            }
            val songs = when (val source = item.source) {
                is SearchTrackSource.Vk -> return@launch
                is SearchTrackSource.Yandex -> {
                    trackRepository.putTracks(listOf(source.track))
                    songRepository.songsForYandexTracks(listOf(source.track))
                }

                is SearchTrackSource.Local -> listOf(source.song)
            }

            playerRepository.playQueue(
                songs = songs,
                startIndex = 0,
                tracklist = dSimpleTracklist(),
            )
            onStarted()
        }
    }

    /** Создаёт общий поиск с отдельным репозиторием VK. */
    class Factory(
        private val repository: SearchRepository,
        private val localMusicRepository: LocalMusicRepository,
        private val trackRepository: TrackRepository,
        private val songRepository: SongRepository,
        private val playerRepository: PlayerRepository,
        private val onAuthorizationRequired: () -> Unit,
        private val vkRepository: com.yellastrodev.dwij.data.repo.VkMusicRepository,
    ) : ViewModelProvider.Factory {

        /** Возвращает модель поиска для Android и desktop. */
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: KClass<T>,
            extras: CreationExtras,
        ): T {
            if (modelClass == SearchModel::class) {
                return SearchModel(
                    repository = repository,
                    localMusicRepository = localMusicRepository,
                    trackRepository = trackRepository,
                    songRepository = songRepository,
                    playerRepository = playerRepository,
                    onAuthorizationRequired = onAuthorizationRequired,
                    vkRepository = vkRepository,
                ) as T
            }

            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 450L
    }
}
