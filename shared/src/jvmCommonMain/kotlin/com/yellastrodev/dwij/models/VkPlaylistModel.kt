package com.yellastrodev.dwij.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.repo.VK_ALL_TRACKS
import com.yellastrodev.dwij.data.repo.VK_MY_TRACKS
import com.yellastrodev.dwij.data.repo.VK_RECOMMENDATION_PREFIX
import com.yellastrodev.dwij.data.repo.VkMusicRepository
import com.yellastrodev.vkmusicsdk.VkAudio
import com.yellastrodev.vkmusicsdk.VkPlaylist
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/** Снимок VK-треклиста, сохраняемый в back stack вместе с моделью экрана. */
data class VkPlaylistUiState(
    val playlist: VkPlaylist? = null,
    val tracks: List<VkAudio> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: DataError? = null,
)

/** Удерживает состав VK при переходе в плеер; рекомендации не требуют обновления личной фонотеки. */
class VkPlaylistModel(
    private val repository: VkMusicRepository,
    private val playlistId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(VkPlaylistUiState())
    val state = mutableState.asStateFlow()
    private val isCollection = playlistId == VK_MY_TRACKS || playlistId == VK_ALL_TRACKS
    private val isRecommendation = playlistId.startsWith(VK_RECOMMENDATION_PREFIX)
    private var loadKey: Triple<Boolean, Long, Long>? = null
    private var loadJob: Job? = null

    /** Повторный вход сохраняет данные; рекомендации игнорируют мутации личной коллекции, смена сессии очищает снимок. */
    fun load(authorized: Boolean, sessionRevision: Long, membershipRevision: Long) {
        val key = Triple(authorized, sessionRevision, if (isRecommendation) 0L else membershipRevision)
        if (loadKey == key) return
        val previous = loadKey
        loadKey = key
        if (previous == null || previous.first != authorized || previous.second != sessionRevision) {
            mutableState.value = VkPlaylistUiState()
        }
        reload(preferLocal = mutableState.value.playlist == null)
    }

    /** Запрашивает актуальный состав, оставляя уже показанные строки на месте. */
    fun refresh() {
        reload(preferLocal = false)
    }

    /** Убирает подтверждённо удалённый трек до фонового обновления серверного снимка. */
    fun removeTrack(fullId: String) {
        mutableState.value = mutableState.value.copy(
            tracks = mutableState.value.tracks.filterNot { it.fullId == fullId },
        )
        refresh()
    }

    /** Загружает кеш и сеть в scope модели; отменённая загрузка не меняет состояние новой сессии. */
    private fun reload(preferLocal: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(isRefreshing = true, error = null)
            try {
                if (preferLocal) {
                    val cached = repository.cachedPlaylist(playlistId)
                    currentCoroutineContext().ensureActive()
                    if (cached != null) {
                        mutableState.value = mutableState.value.copy(
                            playlist = cached.playlist, tracks = cached.tracks, isLoading = false,
                        )
                    }
                }
                if (!repository.authorized.value) return@launch
                val result = repository.getPlaylist(playlistId, preferLocal = false)
                currentCoroutineContext().ensureActive()
                mutableState.value = when (result) {
                    is DataResult.Success -> mutableState.value.copy(
                        playlist = result.value.playlist, tracks = result.value.tracks, isLoading = false,
                    )
                    is DataResult.Failure -> mutableState.value.copy(error = result.error)
                }
            } finally {
                if (currentCoroutineContext().isActive) {
                    mutableState.value = mutableState.value.copy(isLoading = false, isRefreshing = false)
                }
            }
            if (!isCollection && !isRecommendation && repository.authorized.value) repository.refreshMyTracks()
        }
    }

    /** Создаёт отдельную модель для каждого VK-треклиста в back stack. */
    class Factory(
        private val repository: VkMusicRepository,
        private val playlistId: String,
    ) : ViewModelProvider.Factory {
        /** Подключает модель к стандартному владельцу жизненного цикла маршрута. */
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
            require(modelClass == VkPlaylistModel::class)
            return VkPlaylistModel(repository, playlistId) as T
        }
    }
}
