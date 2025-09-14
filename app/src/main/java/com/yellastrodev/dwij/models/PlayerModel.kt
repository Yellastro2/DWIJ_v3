package com.yellastrodev.dwij.models

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yellastrodev.dwij.data.repo.CoverRepository
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.data.repo.TrackRepository
import com.yellastrodev.dwij.entities.dYaTrack
import com.yellastrodev.yandexmusiclib.entities.YaTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlayerModel(
    private val playerRepo: PlayerRepository,
    private val trackRepo: TrackRepository,
    val coverRepo: CoverRepository
)  : ViewModel() {

    suspend fun nextTrack() {
        playerRepo.skipNext()
    }

    suspend fun prevTrack() {
        playerRepo.skipPrev()
    }

    fun playAudio() {
        playerRepo.pause()
    }

    fun seekTo(lng: Long) {
        playerRepo.seekTo(lng)
    }

    fun shuffle() {
        playerRepo.shuffle()
    }

    companion object {
        val TAG = "PlayerModel"
    }

    /**
     * Factory для создания [TracklistModel] с передачей зависимостей.
     */
    class Factory(
        private val playerRepo: PlayerRepository,
        private val trackRepo: TrackRepository,
        val coverRepo: CoverRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PlayerModel::class.java)) {
                Log.d(TAG, "Создаём экземпляр TracklistModel через Factory")
                return PlayerModel(playerRepo, trackRepo, coverRepo) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    // Flow для UI с полным объектом Track
    private val _track = MutableStateFlow<dYaTrack?>(null)
    val track: StateFlow<dYaTrack?> = _track

    val playerState = playerRepo.state

    init {
        // Подписка на изменения ID трека из репо
        viewModelScope.launch {
            playerRepo.currentTrack.collect { trackId ->
                if (trackId != null) {
                    val trackObj = trackRepo.tracks.value[trackId]
                    _track.value = trackObj
                    Log.d(TAG, "trackId=$trackId, trackObj=$trackObj")
                } else {
                    _track.value = null
                    Log.d(TAG, "trackId=null")
                }
            }
        }
    }




}