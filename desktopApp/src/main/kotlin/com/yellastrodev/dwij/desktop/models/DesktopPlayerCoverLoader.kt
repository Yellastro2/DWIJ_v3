package com.yellastrodev.dwij.desktop.models

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.yellastrodev.dwij.data.entities.TrackInstance
import com.yellastrodev.dwij.data.repo.CoverRepository
import com.yellastrodev.dwij.models.PlayerCoverLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.io.File

/**
 * Desktop-реализация обложек для общего PlayerModel.
 *
 * Яндекс-обложки берутся из общего CoverRepository.
 * Для локальных файлов первый прототип ищет sidecar cover/folder/front JPEG/PNG.
 */
class DesktopPlayerCoverLoader(
    private val coverRepository: CoverRepository,
) : PlayerCoverLoader {

    override suspend fun load(
        instance: TrackInstance?,
        maxEdgePx: Int,
    ): ImageBitmap? =
        withContext(
            Dispatchers.IO,
        ) {
            val bytes =
                when (
                    instance
                ) {
                    is TrackInstance.Yandex ->
                        coverRepository
                            .getPlayerTrackCover(
                                instance.track,
                            )
                            ?.bytes

                    is TrackInstance.Local ->
                        localCoverBytes(
                            instance,
                        )

                    null ->
                        null
                }
                    ?: return@withContext null

            runCatching {
                Image
                    .makeFromEncoded(
                        bytes,
                    )
                    .use { image ->
                        image.toComposeImageBitmap()
                    }
            }.getOrNull()
        }

    private fun localCoverBytes(
        instance: TrackInstance.Local,
    ): ByteArray? {
        val audioFile =
            instance.track
                .absolutePath
                ?.let(::File)
                ?.takeIf(File::isFile)
                ?: return null

        val directory =
            audioFile.parentFile
                ?: return null

        val candidates =
            listOf(
                File(
                    directory,
                    "cover.jpg",
                ),
                File(
                    directory,
                    "cover.png",
                ),
                File(
                    directory,
                    "folder.jpg",
                ),
                File(
                    directory,
                    "folder.png",
                ),
                File(
                    directory,
                    "front.jpg",
                ),
                File(
                    directory,
                    "front.png",
                ),
                File(
                    directory,
                    "${audioFile.nameWithoutExtension}.jpg",
                ),
                File(
                    directory,
                    "${audioFile.nameWithoutExtension}.png",
                ),
            )

        return candidates
            .firstOrNull(
                File::isFile,
            )
            ?.readBytes()
    }
}
