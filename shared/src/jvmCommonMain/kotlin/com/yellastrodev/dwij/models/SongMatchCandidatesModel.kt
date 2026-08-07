package com.yellastrodev.dwij.models

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.SongMatchCandidateEntity
import com.yellastrodev.dwij.data.entities.SongMatchCandidateStatus
import com.yellastrodev.dwij.data.entities.TrackInstance
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.data.repo.SongMatchRepository
import com.yellastrodev.dwij.data.repo.SongRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

@Immutable
data class SongMatchCandidateItem(
    val key: String,
    val firstSong: Song,
    val secondSong: Song,
    val status: SongMatchCandidateStatus,
)

@Immutable
data class SongMatchSourceEntry(
    val song: Song,
    val instance: TrackInstance,
) {
    val songId: String
        get() = song.id
}

@Immutable
data class SongMatchCandidatesUiState(
    val isLoading: Boolean = true,
    val candidates: List<SongMatchCandidateItem> = emptyList(),
    val selectedCandidateKey: String? = null,
    val selectedSourceEntries: List<SongMatchSourceEntry> = emptyList(),
    val isMergingSources: Boolean = false,
    val mergeError: Throwable? = null,
)

sealed interface SongMatchCandidatesEvent {
    data object LoadFailed : SongMatchCandidatesEvent
    data class MergeSucceeded(
        val mergedSongId: String,
    ) : SongMatchCandidatesEvent
}

/**
 * Загружает песни для кандидатов, держит выбранную пару и выполняет merge.
 *
 * Не зависит от Android, Compose-навигации, Snackbar и загрузки обложек.
 */
class SongMatchCandidatesModel(
    private val songMatchRepository: SongMatchRepository,
    private val songRepository: SongRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SongMatchCandidatesUiState())
    val state: StateFlow<SongMatchCandidatesUiState> = _state.asStateFlow()

    private val eventChannel = Channel<SongMatchCandidatesEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    init {
        observeCandidates()
    }

    private fun observeCandidates() {
        viewModelScope.launch {
            songMatchRepository.candidates.collectLatest { entities ->
                _state.update { current ->
                    current.copy(isLoading = true)
                }

                try {
                    val songIds = entities
                        .flatMap { candidate ->
                            listOf(
                                candidate.firstSongId,
                                candidate.secondSongId,
                            )
                        }
                        .distinct()

                    val songsById = songRepository
                        .songsByIds(songIds)
                        .associateBy(Song::id)

                    val candidates = entities.mapNotNull { entity ->
                        entity.toItem(songsById)
                    }

                    _state.update { current ->
                        val selectedCandidate = current.selectedCandidateKey
                            ?.let { selectedKey ->
                                candidates.firstOrNull { candidate ->
                                    candidate.key == selectedKey
                                }
                            }

                        current.copy(
                            isLoading = false,
                            candidates = candidates,
                            selectedCandidateKey = selectedCandidate?.key,
                            selectedSourceEntries = selectedCandidate
                                ?.sourceEntries()
                                .orEmpty(),
                            mergeError = if (selectedCandidate == null) {
                                null
                            } else {
                                current.mergeError
                            },
                        )
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    _state.update { current ->
                        current.copy(
                            isLoading = false,
                            candidates = emptyList(),
                            selectedCandidateKey = null,
                            selectedSourceEntries = emptyList(),
                            mergeError = null,
                        )
                    }

                    eventChannel.send(SongMatchCandidatesEvent.LoadFailed)
                }
            }
        }
    }

    fun selectCandidate(candidateKey: String) {
        val candidate = _state.value.candidates
            .firstOrNull { item -> item.key == candidateKey }
            ?: return

        _state.update { current ->
            current.copy(
                selectedCandidateKey = candidate.key,
                selectedSourceEntries = candidate.sourceEntries(),
                mergeError = null,
            )
        }
    }

    fun dismissCandidate() {
        if (_state.value.isMergingSources) return

        _state.update { current ->
            current.copy(
                selectedCandidateKey = null,
                selectedSourceEntries = emptyList(),
                mergeError = null,
            )
        }
    }

    fun mergeSources(selectedInstanceIds: Set<String>) {
        val currentState = _state.value
        if (currentState.isMergingSources) return

        val selectedEntries = currentState.selectedSourceEntries
            .filter { entry -> entry.instance.id in selectedInstanceIds }

        if (selectedEntries.size < 2) return

        _state.update { current ->
            current.copy(
                isMergingSources = true,
                mergeError = null,
            )
        }

        viewModelScope.launch {
            try {
                val sourceSongIds = selectedEntries
                    .mapTo(linkedSetOf(), SongMatchSourceEntry::songId)

                val mergedSongId = songRepository.mergeInstances(
                    selectedEntries.map(SongMatchSourceEntry::instance),
                )

                val mergedSong = requireNotNull(
                    songRepository
                        .songsByIds(listOf(mergedSongId))
                        .firstOrNull(),
                ) {
                    "Объединённая Song $mergedSongId не найдена"
                }

                playerRepository.applyMergedSong(
                    sourceSongIds = sourceSongIds,
                    mergedSong = mergedSong,
                )

                _state.update { current ->
                    current.copy(
                        selectedCandidateKey = null,
                        selectedSourceEntries = emptyList(),
                        isMergingSources = false,
                        mergeError = null,
                    )
                }

                eventChannel.send(
                    SongMatchCandidatesEvent.MergeSucceeded(mergedSongId),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { current ->
                    current.copy(
                        isMergingSources = false,
                        mergeError = error,
                    )
                }
            }
        }
    }

    class Factory(
        private val songMatchRepository: SongMatchRepository,
        private val songRepository: SongRepository,
        private val playerRepository: PlayerRepository,
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: KClass<T>,
            extras: CreationExtras,
        ): T {
            if (modelClass == SongMatchCandidatesModel::class) {
                return SongMatchCandidatesModel(
                    songMatchRepository = songMatchRepository,
                    songRepository = songRepository,
                    playerRepository = playerRepository,
                ) as T
            }

            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

private fun SongMatchCandidateEntity.toItem(
    songsById: Map<String, Song>,
): SongMatchCandidateItem? {
    val firstSong = songsById[firstSongId] ?: return null
    val secondSong = songsById[secondSongId] ?: return null

    return SongMatchCandidateItem(
        key = "$firstSongId:$secondSongId",
        firstSong = firstSong,
        secondSong = secondSong,
        status = if (status == SongMatchCandidateStatus.PENDING.name) {
            SongMatchCandidateStatus.PENDING
        } else {
            SongMatchCandidateStatus.REJECTED
        },
    )
}

private fun SongMatchCandidateItem.sourceEntries(): List<SongMatchSourceEntry> =
    listOf(firstSong, secondSong)
        .flatMap { song ->
            song.instances.map { instance ->
                SongMatchSourceEntry(
                    song = song,
                    instance = instance,
                )
            }
        }
        .distinctBy { entry -> entry.instance.id }
