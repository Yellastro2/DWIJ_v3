package com.yellastrodev.dwij.playback

import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.repo.CoverRepository
import com.yellastrodev.dwij.data.repo.TrackRepository
import com.yellastrodev.yandexmusiclib.YamLogger

/**
 * Загружает байты обложки трека.
 * Не знает ничего об Android Bitmap и Media3.
 */
class TrackCoverLoader(
    private val trackRepository: TrackRepository,
    private val coverRepository: CoverRepository,
    private val logger: YamLogger,
) {
    suspend fun loadPlayerCover(trackId: String): ByteArray? {
        if (trackId.isBlank()) {
            logger.warning(TAG, "[loadPlayerCover] Передан пустой trackId")
            return null
        }

        val track = when (val result = trackRepository.getTrack(trackId)) {
            is DataResult.Success -> result.value
            is DataResult.Failure -> {
                logger.warning(
                    TAG,
                    "[loadPlayerCover] Не удалось получить trackId=$trackId: ${result.error}",
                )
                return null
            }
        }

        val cover = coverRepository.getPlayerTrackCover(track)

        if (cover == null) {
            logger.debug(TAG, "[loadPlayerCover] Обложка отсутствует: trackId=$trackId")
            return null
        }

        return cover.bytes
    }

    private companion object {
        const val TAG = "TrackCoverLoader"
    }
}