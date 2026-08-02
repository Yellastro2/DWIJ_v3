package com.yellastrodev.dwij.models

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.data.entities.dYaLikeTracklist
import com.yellastrodev.dwij.data.repo.CoverRepository
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.data.entities.MusicSource
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.repo.LocalMusicRepository
import com.yellastrodev.dwij.data.repo.PlaylistRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import android.graphics.Bitmap

class PlayerModel(
    private val playerRepo: PlayerRepository,
    val coverRepo: CoverRepository,
    val playlistRepo: PlaylistRepository,
    private val localMusicRepo: LocalMusicRepository,
)  : ViewModel() {

    /**
     * Factory для создания [PlayerModel] с передачей зависимостей.
     */
    class Factory(
        private val playerRepo: PlayerRepository,
        val coverRepo: CoverRepository,
        val playlistRepo: PlaylistRepository,
        private val localMusicRepo: LocalMusicRepository,
    ) : ViewModelProvider.Factory
    {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PlayerModel::class.java)) {
                Log.d(TAG, "Создаём экземпляр TracklistModel через Factory")
                return PlayerModel(
                    playerRepo,
                    coverRepo,
                    playlistRepo,
                    localMusicRepo,
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    /**
     * Полная логическая песня для UI и отдельно фактически выбранный source-инстанс.
     */
    val track: StateFlow<Song?> = playerRepo.currentSong
    val playbackTrack = playerRepo.currentPlaybackTrack

    fun cover(song: Song): Flow<Bitmap> {
        val playback = playerRepo.currentPlaybackTrack.value
            ?.takeIf { track -> track.songId == song.id }
        return when (playback?.source) {
            MusicSource.YANDEX -> coverRepo.getCoverFlow(
                requireNotNull(playback.yandexTrack),
                com.yellastrodev.yandexmusiclib.entities.CoverSize.`400x400`,
            )
            MusicSource.LOCAL -> localMusicRepo.cover(requireNotNull(playback.localTrack))
            null -> song.yandexInstances.firstOrNull()?.let { instance ->
                coverRepo.getCoverFlow(
                    instance.track,
                    com.yellastrodev.yandexmusiclib.entities.CoverSize.`400x400`,
                )
            } ?: song.localInstances.firstOrNull()?.let { instance ->
                localMusicRepo.cover(instance.track)
            } ?: kotlinx.coroutines.flow.emptyFlow()
        }
    }


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


    fun rotate() {
        playerRepo.rotate()
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
        val alpha = (1f * 255).toInt() // 50% прозрачности, можно менять

        val background = ContextCompat.getDrawable(context, R.drawable.background_item_roundrect)!!.mutate()
        (background as? GradientDrawable)?.setColor(
            Color.argb(alpha, Color.red(randomColor), Color.green(randomColor), Color.blue(randomColor))
        )
            colorsForTitle.put(playlistTitle, background)
        }
        return colorsForTitle[playlistTitle]!!

    }

    fun isTrackLiked(): Boolean {
        val yandexTrackId = track.value?.yandexInstances?.firstOrNull()?.track?.id
            ?: return false
        val likeList = playlistRepo.playlists.value.find { it.kind == dYaLikeTracklist.KIND_LIKED }
        return likeList?.tracks?.any { it.trackId == yandexTrackId } ?: false
    }

    suspend fun likeTrack() {
        track.value?.yandexInstances?.firstOrNull()?.track?.id?.let { id ->
            val shouldBeLiked = !isTrackLiked()
            playlistRepo.setTrackLiked(id, shouldBeLiked)
        }
    }


    companion object {
        const val TAG = "PlayerModel"
    }



//    // Flow для UI с полным объектом Track
//    private val _track = MutableStateFlow<dYaTrack?>(null)
//    val track: StateFlow<dYaTrack?> = _track

    val playerState = playerRepo.state
    val playdTracklist = playerRepo.dtracklist
    val shuffleBlock = playerRepo.isShuffleBlock

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
