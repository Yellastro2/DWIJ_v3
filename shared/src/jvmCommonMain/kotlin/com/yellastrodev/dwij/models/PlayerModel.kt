package com.yellastrodev.dwij.models

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.TrackInstance
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.data.repo.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Платформенный загрузчик обложек.
 *
 * PlayerModel знает только про Compose ImageBitmap.
 * Как именно читать MediaStore, файл или сетевую обложку,
 * решает реализация конкретной платформы.
 */
interface PlayerCoverLoader {

    suspend fun load(
        instance: TrackInstance?,
        maxEdgePx: Int,
    ): ImageBitmap?
}

/**
 * Общее состояние и логика плеера.
 *
 * Здесь нет Android Context, Bitmap, Uri, MediaStore и Android R.
 */
class PlayerModel(
    private val playerRepo: PlayerRepository,
    val playlistRepo: PlaylistRepository,
    private val coverLoader: PlayerCoverLoader,
) : ViewModel() {

    /**
     * Текущая логическая песня.
     */
    val track: StateFlow<Song?> =
        playerRepo.currentSong

    /**
     * Конкретный source-инстанс, который сейчас воспроизводится.
     */
    val playbackTrack =
        playerRepo.currentPlaybackTrack

    /**
     * Загружает обложку песни.
     *
     * Если песня сейчас играет, сначала используется реально выбранный
     * source-инстанс. Иначе сначала берётся Яндекс-инстанс, затем локальный.
     */
    fun cover(
        song: Song,
        maxEdgePx: Int = DEFAULT_COVER_MAX_EDGE_PX,
    ): Flow<ImageBitmap?> = flow {
        val currentInstance = playerRepo
            .currentPlaybackTrack
            .value
            ?.takeIf { playback ->
                playback.songId == song.id
            }
            ?.instanceId
            ?.let { instanceId ->
                song.instances.firstOrNull { instance ->
                    instance.id == instanceId
                }
            }

        val fallbackInstance =
            song.yandexInstances.firstOrNull()
                ?: song.localInstances.firstOrNull()

        emit(
            coverLoader.load(
                instance = currentInstance ?: fallbackInstance,
                maxEdgePx = maxEdgePx.coerceAtLeast(1),
            ),
        )
    }.flowOn(Dispatchers.IO)

    /**
     * Загружает обложку конкретного source-инстанса.
     */
    fun cover(
        instance: TrackInstance,
        maxEdgePx: Int = DEFAULT_COVER_MAX_EDGE_PX,
    ): Flow<ImageBitmap?> = flow {
        emit(
            coverLoader.load(
                instance = instance,
                maxEdgePx = maxEdgePx.coerceAtLeast(1),
            ),
        )
    }.flowOn(Dispatchers.IO)

    /**
     * Обновляет Song-снимки текущей очереди после объединения источников.
     */
    fun applyMergedSong(
        sourceSongIds: Set<String>,
        mergedSong: Song,
    ) {
        playerRepo.applyMergedSong(
            sourceSongIds = sourceSongIds,
            mergedSong = mergedSong,
        )
    }

    /** Передаёт подтверждённый Room-снимок Song в уже играющую очередь. */
    fun applyUpdatedSong(song: Song) {
        playerRepo.applyUpdatedSong(song)
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

    /** Меняет лайк на явно запрошенное состояние, не вычисляя его из устаревшей очереди. */
    suspend fun likeTrack(trackId: String, liked: Boolean): DataResult<Unit> {
        if (trackId.isBlank()) {
            return DataResult.Failure(
                DataError.InvalidData(
                    "Для изменения лайка отсутствует Yandex trackId",
                ),
            )
        }

        return playlistRepo.setTrackLiked(
            trackId = trackId,
            liked = liked,
        )
    }

    val playerState =
        playerRepo.state

    val playdTracklist =
        playerRepo.dtracklist

    val shuffleBlock =
        playerRepo.isShuffleBlock

    val playerEvent =
        playerRepo.events

    private companion object {
        const val DEFAULT_COVER_MAX_EDGE_PX = 400
    }
}
