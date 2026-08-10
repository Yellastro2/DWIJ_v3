package com.yellastrodev.dwij.desktop.models

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.yellastrodev.dwij.data.entities.TrackInstance
import com.yellastrodev.dwij.data.repo.CoverRepository
import com.yellastrodev.dwij.desktop.data.source.DesktopAudioMetadataReader
import com.yellastrodev.dwij.models.PlayerCoverLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.io.File

/**
 * Desktop-реализация обложек для общего PlayerModel.
 *
 * Яндекс-обложки берутся из общего CoverRepository.
 * Для локальных файлов сначала читается embedded artwork, затем sidecar.
 */
class DesktopPlayerCoverLoader(
    private val coverRepository: CoverRepository,
    private val metadataReader: DesktopAudioMetadataReader,
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
                        instance.track
                            .absolutePath
                            ?.let(::File)
                            ?.takeIf(
                                File::isFile,
                            )
                            ?.let(
                                metadataReader::readArtwork,
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
}
