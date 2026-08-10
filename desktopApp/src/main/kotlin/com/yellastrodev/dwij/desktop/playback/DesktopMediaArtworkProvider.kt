package com.yellastrodev.dwij.desktop.playback

import com.yellastrodev.dwij.CacheManager
import com.yellastrodev.dwij.data.entities.PlaybackTrack
import com.yellastrodev.dwij.data.repo.CoverRepository
import com.yellastrodev.dwij.desktop.data.source.DesktopAudioMetadataReader
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
 * Local: embedded artwork -> sidecar -> desktop cover cache.
 */
class DesktopMediaArtworkProvider(
    private val coverRepository: CoverRepository,
    private val coverCacheDirectory: File,
    private val cacheManager: CacheManager,
    private val metadataReader: DesktopAudioMetadataReader,
) {

    suspend fun resolve(
        track: PlaybackTrack,
    ): File? =
        withContext(
            Dispatchers.IO,
        ) {
            resolveLocalCoverBytes(
                track,
            )?.let { bytes ->
                return@withContext materializeCover(
                    key =
                        "local:${track.instanceId}:${bytes.sha256()}",
                    bytes =
                        bytes,
                )
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

            materializeCover(
                key =
                    "yandex:${track.instanceId}:${track.artworkUri.orEmpty()}",
                bytes =
                    bytes,
            )
        }

    private fun resolveLocalCoverBytes(
        track: PlaybackTrack,
    ): ByteArray? {
        val audioFile =
            track.localTrack
                ?.absolutePath
                ?.let(::File)
                ?.takeIf(File::isFile)
                ?: return null

        return metadataReader.readArtwork(
            audioFile,
        )
    }

    private suspend fun materializeCover(
        key: String,
        bytes: ByteArray,
    ): File? {
        val extension =
            imageExtension(
                bytes,
            )
                ?: return null

        coverCacheDirectory.mkdirs()

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

    private fun ByteArray.sha256():
            String =
        MessageDigest
            .getInstance(
                "SHA-256",
            )
            .digest(
                this,
            )
            .joinToString(
                separator = "",
            ) { byte ->
                "%02x".format(
                    byte,
                )
            }
}
