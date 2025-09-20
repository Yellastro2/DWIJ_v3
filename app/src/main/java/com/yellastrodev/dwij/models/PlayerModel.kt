package com.yellastrodev.dwij.models

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.data.repo.CoverRepository
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.data.repo.TrackRepository
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.repo.PlaylistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlayerModel(
    private val playerRepo: PlayerRepository,
    private val trackRepo: TrackRepository,
    val coverRepo: CoverRepository,
    val playlistRepo: PlaylistRepository
)  : ViewModel() {

    /**
     * Factory для создания [TracklistModel] с передачей зависимостей.
     */
    class Factory(
        private val playerRepo: PlayerRepository,
        private val trackRepo: TrackRepository,
        val coverRepo: CoverRepository,
        val playlistRepo: PlaylistRepository
    ) : ViewModelProvider.Factory
    {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PlayerModel::class.java)) {
                Log.d(TAG, "Создаём экземпляр TracklistModel через Factory")
                return PlayerModel(playerRepo, trackRepo, coverRepo, playlistRepo) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    /**
     * Flow для UI с полным объектом Track
     * Подписан на трек из плеера: получает переключения между треками проигрывания,
     * Также подписан на данные трека из репозитория. Чтобы ловить изменения плейлистов трека
     */
    val track: StateFlow<dYaTrack?> =
        combine(playerRepo.currentTrack, trackRepo.tracks) { trackId, tracksMap ->
            if (trackId != null) {
                tracksMap[trackId] // всегда актуальный объект из репо
            } else {
                null
            }
        }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)


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

    private val colorIds = listOf(
//				R.color.colorAccent,
        R.color.colorAccent2,
        R.color.colorAccent3,
        R.color.colorAccent4,
        R.color.colorAccent5,
        R.color.colorAccent6
    )
    private val colorsForTitle = HashMap<String, Drawable>()

    /**
     * для текущего тайтла формирует случайный цвет, либо если уже делал для него, то возвращает
     * уже сделанный цвет
     */
    fun getBackground(context: Context, playlistTitle: String): Drawable {

        if (!colorsForTitle.containsKey(playlistTitle)) {

        val randomColorId = colorIds.random()
        val randomColor = ContextCompat.getColor(context, randomColorId) // ← получаем сам цвет
        val alpha = (0.5f * 255).toInt() // 50% прозрачности, можно менять

        val background = ContextCompat.getDrawable(context, R.drawable.background_item_roundrect)!!.mutate()
        (background as? GradientDrawable)?.setColor(
            Color.argb(alpha, Color.red(randomColor), Color.green(randomColor), Color.blue(randomColor))
        )
            colorsForTitle.put(playlistTitle, background)
        }
        return colorsForTitle[playlistTitle]!!

    }

    companion object {
        val TAG = "PlayerModel"
    }



//    // Flow для UI с полным объектом Track
//    private val _track = MutableStateFlow<dYaTrack?>(null)
//    val track: StateFlow<dYaTrack?> = _track

    val playerState = playerRepo.state
    val playTitle = playerRepo.playTitle

    val playerEvent = playerRepo.events

//    init {
//        // Подписка на изменения ID трека из репо
//        viewModelScope.launch {
//            playerRepo.currentTrack.collect { trackId ->
//                if (trackId != null) {
//                    val trackObj = trackRepo.getTrack(trackId)
//                    _track.value = trackObj
//                    Log.d(TAG, "trackId=$trackId, trackObj=$trackObj")
//                } else {
//                    _track.value = null
//                    Log.d(TAG, "trackId=null")
//                }
//            }
//        }
//    }




}