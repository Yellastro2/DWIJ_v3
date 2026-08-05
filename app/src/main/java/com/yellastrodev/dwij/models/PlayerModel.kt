package com.yellastrodev.dwij.models

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.LocalTrackEntity
import com.yellastrodev.dwij.data.entities.MusicSource
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.TrackInstance
import com.yellastrodev.dwij.data.entities.dYaLikeTracklist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.repo.CoverRepository
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.data.repo.PlaylistRepository
import com.yellastrodev.yandexmusiclib.entities.CoverSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class PlayerModel(
    private val context: Context,
    private val playerRepo: PlayerRepository,
    val coverRepo: CoverRepository,
    val playlistRepo: PlaylistRepository,
) : ViewModel() {

    class Factory(
        private val context: Context,
        private val playerRepo: PlayerRepository,
        private val coverRepo: CoverRepository,
        private val playlistRepo: PlaylistRepository,
    ) : ViewModelProvider.Factory {

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PlayerModel::class.java)) {
                Log.d(TAG, "Создаём экземпляр PlayerModel через Factory")

                return PlayerModel(
                    context = context.applicationContext,
                    playerRepo = playerRepo,
                    coverRepo = coverRepo,
                    playlistRepo = playlistRepo,
                ) as T
            }

            throw IllegalArgumentException(
                "Unknown ViewModel class: ${modelClass.name}",
            )
        }
    }

    /**
     * Полная логическая песня для UI и отдельно фактически выбранный source-инстанс.
     */
    val track: StateFlow<Song?> = playerRepo.currentSong
    val playbackTrack = playerRepo.currentPlaybackTrack

    /**
     * Возвращает обложку текущей песни как Android Bitmap.
     *
     * Яндекс-обложки загружаются новым shared CoverRepository.
     * Локальные обложки пока читаются Android-кодом из MediaStore или файла.
     */
    fun cover(song: Song): Flow<Bitmap> = flow {
        val playback = playerRepo.currentPlaybackTrack.value
            ?.takeIf { current -> current.songId == song.id }

        val bitmap = when (playback?.source) {
            MusicSource.YANDEX -> {
                loadYandexCover(
                    track = requireNotNull(playback.yandexTrack),
                    size = CoverSize.`400x400`,
                )
            }

            MusicSource.LOCAL -> {
                loadLocalCover(
                    track = requireNotNull(playback.localTrack),
                )
            }

            null -> {
                song.yandexInstances.firstOrNull()?.let { instance ->
                    loadYandexCover(
                        track = instance.track,
                        size = CoverSize.`400x400`,
                    )
                } ?: song.localInstances.firstOrNull()?.let { instance ->
                    loadLocalCover(instance.track)
                } ?: placeholderCover()
            }
        }

        emit(bitmap)
    }.flowOn(Dispatchers.IO)

    /**
     * Загружает обложку конкретного source-инстанса,
     * не учитывая текущий playback.
     */
    fun cover(instance: TrackInstance): Flow<Bitmap> = flow {
        val bitmap = when (instance) {
            is TrackInstance.Yandex -> {
                loadYandexCover(
                    track = instance.track,
                    size = CoverSize.`400x400`,
                )
            }

            is TrackInstance.Local -> {
                loadLocalCover(instance.track)
            }
        }

        emit(bitmap)
    }.flowOn(Dispatchers.IO)

    private suspend fun loadYandexCover(
        track: dYaTrack,
        size: CoverSize,
    ): Bitmap {
        val coverData = coverRepo.getTrackCover(
            track = track,
            size = size,
        ) ?: return placeholderCover()

        return BitmapFactory.decodeByteArray(
            coverData.bytes,
            0,
            coverData.bytes.size,
        ) ?: placeholderCover()
    }

    private fun loadLocalCover(
        track: LocalTrackEntity,
    ): Bitmap {
        val albumCover = track.albumId?.let { albumId ->
            runCatching {
                context.contentResolver.openInputStream(
                    Uri.parse(
                        "content://media/external/audio/albumart/$albumId",
                    ),
                )?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            }.getOrNull()
        }

        if (albumCover != null) {
            return albumCover.downscaled(LOCAL_COVER_MAX_EDGE_PX)
        }

        val retriever = MediaMetadataRetriever()

        val embeddedCover = try {
            retriever.setDataSource(
                context,
                Uri.parse(track.contentUri),
            )

            retriever.embeddedPicture?.let { bytes ->
                BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                )
            }
        } catch (error: Exception) {
            Log.d(
                TAG,
                "Не удалось прочитать локальную обложку: ${track.instanceId}",
                error,
            )
            null
        } finally {
            runCatching {
                retriever.release()
            }
        }

        return embeddedCover
            ?.downscaled(LOCAL_COVER_MAX_EDGE_PX)
            ?: placeholderCover()
    }

    private fun placeholderCover(): Bitmap {
        return requireNotNull(
            ContextCompat.getDrawable(
                context,
                R.drawable.ic_player_play_v2,
            ),
        ).toBitmap()
    }

    private fun Bitmap.downscaled(
        maxEdge: Int,
    ): Bitmap {
        val largestEdge = maxOf(width, height)

        if (largestEdge <= maxEdge || largestEdge <= 0) {
            return this
        }

        val scale = maxEdge.toFloat() / largestEdge

        val scaled = Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )

        if (scaled !== this) {
            recycle()
        }

        return scaled
    }

    /**
     * Обновляет Song-снимки текущей очереди после Room-объединения источников.
     */
    fun applyMergedSong(
        sourceSongIds: Set<String>,
        mergedSong: Song,
    ) {
        playerRepo.applyMergedSong(
            sourceSongIds,
            mergedSong,
        )
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

    fun seekTo(position: Long) {
        playerRepo.seekTo(position)
    }

    fun shuffle() {
        playerRepo.shuffle()
    }

    fun rotate() {
        playerRepo.rotate()
    }

    private val colorIds = listOf(
        R.color.colorAccent2,
        R.color.colorAccent3,
        R.color.colorAccent4,
        R.color.colorAccent5,
        R.color.colorAccent6,
    )

    private val colorsForTitle = HashMap<String, Drawable>()

    /**
     * Для текущего тайтла формирует случайный цвет.
     * Для уже известного тайтла возвращает ранее созданный цвет.
     */
    fun getBackground(
        context: Context,
        playlistTitle: String,
    ): Drawable {
        if (!colorsForTitle.containsKey(playlistTitle)) {
            val randomColorId = colorIds.random()
            val randomColor = ContextCompat.getColor(
                context,
                randomColorId,
            )

            val background = requireNotNull(
                ContextCompat.getDrawable(
                    context,
                    R.drawable.background_item_roundrect,
                ),
            ).mutate()

            (background as? GradientDrawable)?.setColor(
                Color.argb(
                    255,
                    Color.red(randomColor),
                    Color.green(randomColor),
                    Color.blue(randomColor),
                ),
            )

            colorsForTitle[playlistTitle] = background
        }

        return requireNotNull(
            colorsForTitle[playlistTitle],
        )
    }

    fun isTrackLiked(): Boolean {
        val yandexTrackId = track.value
            ?.yandexInstances
            ?.firstOrNull()
            ?.track
            ?.id
            ?: return false

        val likeList = playlistRepo.playlists.value.find { playlist ->
            playlist.kind == dYaLikeTracklist.KIND_LIKED
        }

        return likeList?.tracks?.any { item ->
            item.trackId == yandexTrackId
        } ?: false
    }

    /**
     * Меняет лайк текущего Яндекс-инстанса
     * и возвращает результат вплоть до UI.
     */
    suspend fun likeTrack(): DataResult<Unit> {
        val trackId = track.value
            ?.yandexInstances
            ?.firstOrNull()
            ?.track
            ?.id
            ?: return DataResult.Failure(
                DataError.InvalidData(
                    "У текущей песни отсутствует Яндекс-инстанс",
                ),
            )

        return playlistRepo.setTrackLiked(
            trackId = trackId,
            liked = !isTrackLiked(),
        )
    }

    val playerState = playerRepo.state
    val playdTracklist = playerRepo.dtracklist
    val shuffleBlock = playerRepo.isShuffleBlock
    val playerEvent = playerRepo.events

    companion object {
        const val TAG = "PlayerModel"

        private const val LOCAL_COVER_MAX_EDGE_PX = 400
    }
}