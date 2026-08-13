package com.yellastrodev.dwij.models

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.dTracklist
import com.yellastrodev.dwij.data.repo.CatalogAlbumPage
import com.yellastrodev.dwij.data.repo.CatalogArtistPage
import com.yellastrodev.dwij.data.repo.CatalogRepository
import com.yellastrodev.dwij.data.repo.CoverData
import com.yellastrodev.dwij.data.repo.CoverRepository
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.yamusicsdk.entities.CoverSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.reflect.KClass

/** Поддержанные разновидности экранов каталога. */
enum class CatalogObjectKind {
    Artist,
    Album,
}

/** Единый UI-снимок минимальных экранов артиста и альбома. */
@Immutable
data class CatalogObjectUiState(
    val kind: CatalogObjectKind = CatalogObjectKind.Artist,
    val externalId: Int? = null,
    val title: String = "",
    val artistNames: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val description: String? = null,
    val coverUri: String? = null,
    val releaseDate: String? = null,
    val year: Int? = null,
    val likesCount: Long? = null,
    val trackCount: Int? = null,
    val lastMonthListeners: Long? = null,
    val metadataSource: String? = null,
    val tracks: List<Song> = emptyList(),
    val isLoading: Boolean = false,
    val error: DataError? = null,
)

/** Загружает один объект каталога и управляет его очередью воспроизведения. */
class CatalogObjectModel(
    private val catalogRepository: CatalogRepository,
    private val coverRepository: CoverRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(CatalogObjectUiState())
    val state: StateFlow<CatalogObjectUiState> = _state.asStateFlow()

    fun load(kind: CatalogObjectKind, externalId: Int, force: Boolean = false) {
        if (!force && _state.value.externalId == externalId && _state.value.kind == kind) return

        _state.value = _state.value.copy(
            kind = kind,
            externalId = externalId,
            isLoading = true,
            error = null,
        )
        viewModelScope.launch {
            val result: DataResult<Any> = try {
                when (kind) {
                    CatalogObjectKind.Artist -> catalogRepository.artist(externalId)
                    CatalogObjectKind.Album -> catalogRepository.album(externalId)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                DataResult.Failure(DataError.Unknown(error))
            }
            _state.value = when (result) {
                is DataResult.Success -> when (val page = result.value) {
                    is CatalogArtistPage -> page.toUiState(externalId)
                    is CatalogAlbumPage -> page.toUiState(externalId)
                    else -> error("Неизвестный тип страницы каталога")
                }
                is DataResult.Failure -> _state.value.copy(
                    isLoading = false,
                    error = result.error,
                )
            }
        }
    }

    fun refresh() {
        val externalId = _state.value.externalId ?: return
        load(_state.value.kind, externalId, force = true)
    }

    fun play(index: Int): Boolean {
        val songs = _state.value.tracks
        if (index !in songs.indices) return false
        viewModelScope.launch {
            playerRepository.playQueue(
                songs = songs,
                startIndex = index,
                tracklist = CatalogTracklist(
                    kind = _state.value.kind,
                    externalId = requireNotNull(_state.value.externalId),
                    title = _state.value.title,
                ),
            )
        }
        return true
    }

    suspend fun objectCover(): CoverData? {
        val current = _state.value
        val uri = current.coverUri ?: return null
        val externalId = current.externalId ?: return null
        return withContext(Dispatchers.IO) {
            coverRepository.getRemoteCover(
                entityType = current.kind.name.lowercase(),
                entityId = externalId.toString(),
                url = uri,
                size = CoverSize.`400x400`,
            )
        }
    }

    suspend fun trackCover(songId: String): CoverData? {
        val track = _state.value.tracks
            .firstOrNull { it.id == songId }
            ?.yandexInstances
            ?.firstOrNull()
            ?.track
            ?: return null
        return withContext(Dispatchers.IO) {
            coverRepository.getTrackCover(track, CoverSize.`100x100`)
        }
    }

    private fun CatalogArtistPage.toUiState(externalId: Int) = CatalogObjectUiState(
        kind = CatalogObjectKind.Artist,
        externalId = externalId,
        title = name,
        genres = genres,
        coverUri = coverUri,
        likesCount = likesCount,
        trackCount = trackCount,
        lastMonthListeners = lastMonthListeners,
        metadataSource = metadataSource,
        tracks = tracks,
    )

    private fun CatalogAlbumPage.toUiState(externalId: Int) = CatalogObjectUiState(
        kind = CatalogObjectKind.Album,
        externalId = externalId,
        title = title,
        artistNames = artistNames,
        description = description,
        coverUri = coverUri,
        releaseDate = releaseDate,
        year = year,
        likesCount = likesCount,
        metadataSource = metadataSource,
        tracks = tracks,
    )

    class Factory(
        private val catalogRepository: CatalogRepository,
        private val coverRepository: CoverRepository,
        private val playerRepository: PlayerRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
            if (modelClass == CatalogObjectModel::class) {
                return CatalogObjectModel(
                    catalogRepository,
                    coverRepository,
                    playerRepository,
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

private data class CatalogTracklist(
    val kind: CatalogObjectKind,
    val externalId: Int,
    val title: String,
) : dTracklist {
    override fun getdId(): String = "${kind.name.lowercase()}:$externalId"
    override fun getDTitle(): String = title
    override fun getType(): String = kind.name.lowercase()
    override fun getWaveId(): String = ""
}
