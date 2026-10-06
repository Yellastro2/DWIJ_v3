package com.yellastrodev.dwij.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.repo.VkMusicRepository
import com.yellastrodev.vkmusicsdk.VkAudio
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Состояние загрузки сетки и выбранного трека, переживающее переходы между маршрутами. */
data class VkPlaylistGridUiState(
    val loading: Boolean,
    val refreshing: Boolean = false,
    val pickedTrack: VkAudio? = null,
    val error: DataError? = null,
)

/** Обновляет сетку один раз на сессию маршрута, не теряя выбранный трек при возврате. */
class VkPlaylistGridModel(
    private val repository: VkMusicRepository,
    private val trackToAdd: String?,
) : ViewModel() {
    private val mutableState = MutableStateFlow(VkPlaylistGridUiState(!repository.hasCachedPlaylists()))
    val state = mutableState.asStateFlow()
    private var loadKey: Pair<Boolean, Long>? = null
    private var loadJob: Job? = null

    /** Возврат к той же сессии не повторяет сеть; при смене аккаунта выбранное аудио очищается. */
    fun load(authorized: Boolean, sessionRevision: Long) {
        val key = authorized to sessionRevision
        if (loadKey == key) return
        loadKey = key
        loadJob?.cancel()
        mutableState.value = VkPlaylistGridUiState(authorized && !repository.hasCachedPlaylists())
        if (authorized) refresh()
    }

    /** Обновляет плитки и выбранное аудио; сведения о членстве догружает после показа сетки. */
    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(refreshing = true, error = null)
            try {
                val grid = repository.refreshPlaylists()
                currentCoroutineContext().ensureActive()
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = (grid as? DataResult.Failure)?.error,
                )
                if (trackToAdd != null && mutableState.value.pickedTrack == null) {
                    val audio = repository.getAudio(trackToAdd.removePrefix("vk:"))
                    currentCoroutineContext().ensureActive()
                    mutableState.value = when (audio) {
                        is DataResult.Success -> mutableState.value.copy(pickedTrack = audio.value)
                        is DataResult.Failure -> mutableState.value.copy(error = audio.error)
                    }
                }
            } finally {
                if (currentCoroutineContext().isActive) {
                    mutableState.value = mutableState.value.copy(loading = false, refreshing = false)
                }
            }
            if (trackToAdd != null && mutableState.value.pickedTrack != null) {
                val membership = repository.refreshPlaylistMemberships()
                currentCoroutineContext().ensureActive()
                if (membership is DataResult.Failure) mutableState.value = mutableState.value.copy(error = membership.error)
            }
        }
    }
}
