package com.yellastrodev.dwij.playback

import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.repo.CoverRepository
import com.yellastrodev.dwij.data.repo.TrackRepository
import com.yellastrodev.yandexmusiclib.YamLogger
import com.yellastrodev.yandexmusiclib.entities.CoverSize

/**
 * Загружает байты обложки трека.
 *
 * Не знает ничего об Android Bitmap, Media3 и уведомлениях.
 */
class TrackCoverLoader(
    private val trackRepository: TrackRepository,
    private val coverRepository: CoverRepository,
    private val logger: YamLogger,
) {

    /**
     * Загружает компактную обложку для системного уведомления.
     */
    suspend fun loadNotificationCover(
        trackId: String,
    ): ByteArray? =
        load(
            trackId = trackId,
            size = CoverSize.`100x100`,
        )

    suspend fun load(
        trackId: String,
        size: CoverSize,
    ): ByteArray? {
        if (trackId.isBlank()) {
            logger.warning(
                TAG,
                "[load] Передан пустой trackId",
            )
            return null
        }

        val track = when (
            val result = trackRepository.getTrack(trackId)
        ) {
            is DataResult.Success -> {
                result.value
            }

            is DataResult.Failure -> {
                logger.warning(
                    TAG,
                    "[load] Не удалось получить трек " +
                            "trackId=$trackId: ${result.error}",
                )
                return null
            }
        }

        val cover = coverRepository.getTrackCover(
            track = track,
            size = size,
        )

        if (cover == null) {
            logger.debug(
                TAG,
                "[load] Обложка отсутствует: " +
                        "trackId=$trackId, size=$size",
            )
            return null
        }

        return cover.bytes
    }

    private companion object {
        const val TAG = "TrackCoverLoader"
    }
}
