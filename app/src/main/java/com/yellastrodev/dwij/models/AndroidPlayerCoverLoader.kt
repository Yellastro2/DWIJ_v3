package com.yellastrodev.dwij.models

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.yellastrodev.dwij.data.entities.LocalTrackEntity
import com.yellastrodev.dwij.data.entities.TrackInstance
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.repo.CoverRepository
import com.yellastrodev.yandexmusiclib.entities.CoverSize

/**
 * Android-реализация загрузки обложек.
 *
 * Только этот класс знает про Context, MediaStore,
 * MediaMetadataRetriever и Android Bitmap.
 */
class AndroidPlayerCoverLoader(
    context: Context,
    private val coverRepository: CoverRepository,
) : PlayerCoverLoader {

    private val context =
        context.applicationContext

    override suspend fun load(
        instance: TrackInstance?,
        maxEdgePx: Int,
    ): ImageBitmap? {
        val bitmap = when (instance) {
            is TrackInstance.Yandex -> {
                loadYandexCover(instance.track)
            }

            is TrackInstance.Local -> {
                loadLocalCover(instance.track)
            }

            null -> null
        } ?: return null

        return bitmap
            .downscaled(
                maxEdge = maxEdgePx.coerceAtLeast(1),
            )
            .asImageBitmap()
    }

    private suspend fun loadYandexCover(
        track: dYaTrack,
    ): Bitmap? {
        val coverData = coverRepository.getTrackCover(
            track = track,
            size = CoverSize.`400x400`,
        ) ?: return null

        return BitmapFactory.decodeByteArray(
            coverData.bytes,
            0,
            coverData.bytes.size,
        )
    }

    private fun loadLocalCover(
        track: LocalTrackEntity,
    ): Bitmap? {
        loadAlbumCover(track)?.let { bitmap ->
            return bitmap
        }

        return loadEmbeddedCover(track)
    }

    private fun loadAlbumCover(
        track: LocalTrackEntity,
    ): Bitmap? {
        val albumId =
            track.albumId
                ?: return null

        return runCatching {
            context.contentResolver
                .openInputStream(
                    Uri.parse(
                        "content://media/external/audio/albumart/$albumId",
                    ),
                )
                ?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
        }.onFailure { error ->
            Log.d(
                TAG,
                "Не удалось прочитать обложку альбома: " +
                        "instanceId=${track.instanceId}",
                error,
            )
        }.getOrNull()
    }

    private fun loadEmbeddedCover(
        track: LocalTrackEntity,
    ): Bitmap? {
        val retriever =
            MediaMetadataRetriever()

        return try {
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
                "Не удалось прочитать встроенную обложку: " +
                        "instanceId=${track.instanceId}",
                error,
            )

            null
        } finally {
            runCatching {
                retriever.release()
            }
        }
    }

    private fun Bitmap.downscaled(
        maxEdge: Int,
    ): Bitmap {
        val largestEdge =
            maxOf(width, height)

        if (
            largestEdge <= maxEdge ||
            largestEdge <= 0
        ) {
            return this
        }

        val scale =
            maxEdge.toFloat() / largestEdge

        val scaled = Bitmap.createScaledBitmap(
            this,
            (width * scale)
                .toInt()
                .coerceAtLeast(1),
            (height * scale)
                .toInt()
                .coerceAtLeast(1),
            true,
        )

        if (scaled !== this) {
            recycle()
        }

        return scaled
    }

    private companion object {
        const val TAG =
            "AndroidPlayerCoverLoader"
    }
}