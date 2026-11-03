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
import com.yellastrodev.yamusicsdk.YamLogger
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

/** Один элемент поисковой выдачи: трек, сущность либо нижняя граница личной секции VK. */
@Immutable
sealed interface SearchResultItemUiModel {
    val key: String
    val coverUri: String?

    /** Разделитель под личными треками; остаётся на границе секций после раскрытия. */
    data class AddedDivider(val expanded: Boolean, val total: Int) : SearchResultItemUiModel {
        override val key: String = "vk:added-divider"
        override val coverUri: String? = null
    }

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

/** Снимок поиска, независимой дозагрузки и подготовки аудио; ревизия сбрасывает прокрутку нового запроса. */
@Immutable
data class SearchUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val results: List<SearchResultItemUiModel> = emptyList(),
    val error: DataError? = null,
    val isPreparingPlayback: Boolean = false,
    val searchRevision: Long = 0,
    val hasMoreVkResults: Boolean = false,
    val isLoadingNextPage: Boolean = false,
    val nextPageError: DataError? = null,
)

/**
 * Управляет debounce, отменой устаревшего запроса и общей выдачей Яндекс, локальных и VK-треков.
 * Дозагружает глобальный VK-поиск без сброса личной секции и запускает снимок видимой выдачи с соседями.
 */
class SearchModel(
    private val repository: SearchRepository,
    private val localMusicRepository: LocalMusicRepository,
    private val trackRepository: TrackRepository,
    private val songRepository: SongRepository,
    private val playerRepository: PlayerRepository,
    private val onAuthorizationRequired: () -> Unit,
    private val vkRepository: com.yellastrodev.dwij.data.repo.VkMusicRepository,
    private val logger: YamLogger,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var selectedSource = com.yellastrodev.dwij.HomeMusicSource.Yandex
    private var playJob: Job? = null
    private var vkAddedResults = emptyList<SearchResultItemUiModel.Track>()
    private var vkGlobalResults = emptyList<SearchResultItemUiModel.Track>()
    private var nextPageJob: Job? = null
    private var nextVkOffset: Int? = null
    private var searchRevision = 0L
    private var lastVkAuthorization: Boolean? = null
    private var lastVkSessionRevision: Long? = null

    /** Обновляет введённый текст и заменяет ожидающий сетевой запрос новым. */
    fun updateQuery(query: String) {
        if (_state.value.query == query) return

        _state.value = _state.value.copy(query = query)
        scheduleSearch("изменён текст")
    }

    /** Смена источника отменяет прежний запрос и повторяет поиск в его хранилище. */
    fun setSource(source: com.yellastrodev.dwij.HomeMusicSource) {
        if (selectedSource == source) return

        selectedSource = source
        scheduleSearch("изменён источник")
    }

    /** Отменяет прежнюю выдачу, сбрасывает раскрытие и ищет только в выбранном источнике. */
    private fun scheduleSearch(reason: String) {
        searchJob?.cancel()
        nextPageJob?.cancel()
        nextVkOffset = null
        searchRevision += 1
        vkAddedResults = emptyList()
        vkGlobalResults = emptyList()

        val query = _state.value.query.trim()

        if (query.isBlank()) {
            _state.value = SearchUiState(searchRevision = searchRevision)
            return
        }

        _state.value = _state.value.copy(
            isLoading = true,
            hasSearched = false,
            results = emptyList(),
            error = null,
            searchRevision = searchRevision,
            hasMoreVkResults = false,
            isLoadingNextPage = false,
            nextPageError = null,
        )
        logger.info("SearchModel", "[scheduleSearch] Причина=$reason, источник=$selectedSource, ревизия=$searchRevision, offset=0")

        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)

            try {
                if (selectedSource == com.yellastrodev.dwij.HomeMusicSource.Vk) {
                    when (val result = vkRepository.searchSections(query)) {
                        is DataResult.Success -> {
                            vkAddedResults = result.value.added.map { it.toSearchItem() }
                            vkGlobalResults = result.value.global.map { it.toSearchItem() }
                            nextVkOffset = result.value.nextOffset
                            _state.value = _state.value.copy(
                                isLoading = false, hasSearched = true, error = null,
                                results = vkResults(expanded = false),
                                hasMoreVkResults = nextVkOffset != null,
                                nextPageError = result.value.globalError,
                            )
                        }
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

    /** Отличает реальную смену авторизации/сессии от повторного входа того же экрана в композицию. */
    fun onVkSessionChanged(authorized: Boolean, revision: Long) {
        val changed = lastVkAuthorization != null &&
            (lastVkAuthorization != authorized || lastVkSessionRevision != revision)
        lastVkAuthorization = authorized
        lastVkSessionRevision = revision
        if (changed && selectedSource == com.yellastrodev.dwij.HomeMusicSource.Vk) {
            scheduleSearch("изменена сессия VK")
        }
    }

    /** Добавляет одну страницу; параллельные вызовы, устаревшие ответы и сброс раскрытия исключены. */
    fun loadNextVkPage() {
        val offset = nextVkOffset ?: return
        if (selectedSource != com.yellastrodev.dwij.HomeMusicSource.Vk ||
            _state.value.isLoading || _state.value.isLoadingNextPage) return
        val revision = searchRevision
        val session = vkRepository.sessionRevision.value
        val query = _state.value.query.trim()
        _state.value = _state.value.copy(isLoadingNextPage = true, nextPageError = null)
        logger.info("SearchModel", "[loadNextVkPage] Ревизия=$revision, offset=$offset")
        nextPageJob = viewModelScope.launch {
            val result = vkRepository.searchSections(query, offset)
            if (revision != searchRevision) return@launch
            if (session != vkRepository.sessionRevision.value) {
                scheduleSearch("сессия VK изменилась при дозагрузке")
                return@launch
            }
            when (result) {
                is DataResult.Success -> {
                    val expanded = _state.value.results.filterIsInstance<SearchResultItemUiModel.AddedDivider>()
                        .firstOrNull()?.expanded == true
                    val previous = vkGlobalResults.map { (it.source as SearchTrackSource.Vk).track }
                    vkGlobalResults = vkRepository.distinctSearchTracks(previous + result.value.global)
                        .map { it.toSearchItem() }
                    nextVkOffset = result.value.nextOffset
                    _state.value = _state.value.copy(
                        results = vkResults(expanded), isLoadingNextPage = false,
                        hasMoreVkResults = nextVkOffset != null, nextPageError = result.value.globalError,
                    )
                    logger.info("SearchModel", "[loadNextVkPage] Ревизия=$revision, получен offset=$offset, " +
                        "следующий=$nextVkOffset, глобальных накоплено=${vkGlobalResults.size}")
                }
                is DataResult.Failure -> {
                    _state.value = _state.value.copy(isLoadingNextPage = false, nextPageError = result.error)
                    logger.warning("SearchModel", "[loadNextVkPage] Ошибка страницы offset=$offset; список сохранён")
                }
            }
        }
    }

    /** Меняет число видимых личных треков без повторного запроса VK и изменения глобальной части. */
    fun toggleAddedSection() {
        val divider = _state.value.results.filterIsInstance<SearchResultItemUiModel.AddedDivider>().firstOrNull()
            ?: return
        if (divider.total <= VK_ADDED_PREVIEW_COUNT) return
        _state.value = _state.value.copy(results = vkResults(expanded = !divider.expanded))
    }

    /** Собирает личные треки, их нижний разделитель и глобальную часть в порядке воспроизведения. */
    private fun vkResults(expanded: Boolean): List<SearchResultItemUiModel> = buildList {
        if (vkAddedResults.isEmpty() && vkGlobalResults.isEmpty()) return@buildList
        addAll(if (expanded) vkAddedResults else vkAddedResults.take(VK_ADDED_PREVIEW_COUNT))
        add(SearchResultItemUiModel.AddedDivider(expanded, vkAddedResults.size))
        addAll(vkGlobalResults)
    }

    /** Превращает VK-аудио в общий трек поиска с устойчивым source-id. */
    private fun com.yellastrodev.vkmusicsdk.VkAudio.toSearchItem() = SearchResultItemUiModel.Track(
        key = "vk:$fullId", coverUri = coverUrl,
        row = TrackListItemUiModel(
            key = "search:vk:$fullId", trackId = "vk:$fullId",
            title = title, artist = artistNames.joinToString(", "),
        ),
        source = SearchTrackSource.Vk(this),
    )

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
    fun retrySearch() { scheduleSearch("повтор пользователем") }

    /** Запускает снимок треков текущей выдачи с выбранной позиции; альбомы и артисты в очередь не входят. */
    fun playTrack(item: SearchResultItemUiModel.Track, onStarted: () -> Unit = {}) {
        if (playJob?.isActive == true) return
        val tracks = _state.value.results.filterIsInstance<SearchResultItemUiModel.Track>()
            .filter { it.source::class == item.source::class }
        val startIndex = tracks.indexOfFirst { it.key == item.key }
        if (startIndex < 0) return
        playJob = viewModelScope.launch {
            val vkSource = item.source as? SearchTrackSource.Vk
            if (vkSource != null) {
                _state.value = _state.value.copy(isPreparingPlayback = true, error = null)
                try {
                    val audio = tracks.map { (it.source as SearchTrackSource.Vk).track }
                    when (val result = vkRepository.playSearch(audio, startIndex, playerRepository)) {
                        is DataResult.Success -> onStarted()
                        is DataResult.Failure -> _state.value = _state.value.copy(error = result.error)
                    }
                } finally {
                    _state.value = _state.value.copy(isPreparingPlayback = false)
                }
                return@launch
            }
            val songs = when (item.source) {
                is SearchTrackSource.Vk -> return@launch
                is SearchTrackSource.Yandex -> {
                    val yandexTracks = tracks.map { (it.source as SearchTrackSource.Yandex).track }
                    trackRepository.putTracks(yandexTracks)
                    songRepository.songsForYandexTracks(yandexTracks)
                }

                is SearchTrackSource.Local -> tracks.map { (it.source as SearchTrackSource.Local).song }
            }

            if (songs.size != tracks.size) {
                _state.value = _state.value.copy(error = DataError.InvalidData("Не удалось собрать треки поиска. Повторите попытку"))
                return@launch
            }
            val started = playerRepository.tryPlayQueue(
                songs = songs,
                startIndex = startIndex,
                tracklist = dSimpleTracklist(),
            )
            if (started) onStarted()
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
        private val logger: YamLogger,
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
                    logger = logger,
                ) as T
            }

            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 450L
        const val VK_ADDED_PREVIEW_COUNT = 4
    }
}
