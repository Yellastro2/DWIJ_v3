package com.yellastrodev.dwij.desktop.playback

import com.yellastrodev.dwij.CacheManager
import com.yellastrodev.dwij.data.entities.PlaybackTrack
import com.yellastrodev.dwij.data.repo.CoverRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Даёт Windows Media Session обычный локальный image File.
 *
 * Yandex:
 * CoverRepository -> существующий memory/disk/network cache ->
 * SMTC-friendly JPG/PNG в desktop cover cache.
 *
 * Local:
 * используется существующий sidecar cover/folder/front JPG/PNG.
 */
class DesktopMediaArtworkProvider(
    private val coverRepository: CoverRepository,
    private val coverCacheDirectory: File,
    private val cacheManager: CacheManager,
) {

    suspend fun resolve(
        track: PlaybackTrack,
    ): File? =
        withContext(
            Dispatchers.IO,
        ) {
            resolveLocalCover(
                track,
            )?.let { file ->
                file.setLastModified(
                    System.currentTimeMillis(),
                )

                return@withContext file
            }

            val yandexTrack =
                track.yandexTrack
                    ?: return@withContext null

            val bytes =
                coverRepository
                    .getPlayerTrackCover(
                        yandexTrack,
                    )
                    ?.bytes
                    ?: return@withContext null

            materializeYandexCover(
                track =
                    track,
                bytes =
                    bytes,
            )
        }

    private fun resolveLocalCover(
        track: PlaybackTrack,
    ): File? {
        val audioFile =
            track.localTrack
                ?.absolutePath
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
    }

    private suspend fun materializeYandexCover(
        track: PlaybackTrack,
        bytes: ByteArray,
    ): File? {
        val extension =
            imageExtension(
                bytes,
            )
                ?: return null

        coverCacheDirectory.mkdirs()

        val key =
            buildString {
                append(
                    track.instanceId,
                )
                append('|')
                append(
                    track.artworkUri.orEmpty(),
                )
            }

        val target =
            File(
                coverCacheDirectory,
                "smtc-${key.sha256()}.$extension",
            )

        if (
            target.isFile &&
            target.length() ==
            bytes.size.toLong()
        ) {
            target.setLastModified(
                System.currentTimeMillis(),
            )

            return target
        }

        val temporary =
            File.createTempFile(
                "smtc-cover-",
                ".tmp",
                coverCacheDirectory,
            )

        try {
            temporary.writeBytes(
                bytes,
            )

            if (
                !temporary.renameTo(
                    target,
                )
            ) {
                temporary.copyTo(
                    target =
                        target,
                    overwrite =
                        true,
                )
            }
        } finally {
            temporary.delete()
        }

        target.setLastModified(
            System.currentTimeMillis(),
        )

        cacheManager
            .ensureWithinLimit()

        return target
            .takeIf(
                File::isFile,
            )
    }

    private fun imageExtension(
        bytes: ByteArray,
    ): String? {
        if (
            bytes.size >= 3 &&
            bytes[0] ==
            0xFF.toByte() &&
            bytes[1] ==
            0xD8.toByte() &&
            bytes[2] ==
            0xFF.toByte()
        ) {
            return "jpg"
        }

        if (
            bytes.size >= 8 &&
            bytes[0] ==
            0x89.toByte() &&
            bytes[1] ==
            0x50.toByte() &&
            bytes[2] ==
            0x4E.toByte() &&
            bytes[3] ==
            0x47.toByte()
        ) {
            return "png"
        }

        return null
    }

    private fun String.sha256():
            String =
        MessageDigest
            .getInstance(
                "SHA-256",
            )
            .digest(
                toByteArray(
                    Charsets.UTF_8,
                ),
            )
            .joinToString(
                separator = "",
            ) { byte ->
                "%02x".format(
                    byte,
                )
            }
}