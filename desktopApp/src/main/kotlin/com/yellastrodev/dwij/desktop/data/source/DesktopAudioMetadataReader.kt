package com.yellastrodev.dwij.desktop.data.source

import com.yellastrodev.yamusicsdk.YamLogger
import org.jaudiotagger.audio.AudioFile
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/**
 * Читает теги, длительность и встроенную обложку локального аудиофайла.
 *
 * Ошибка или отсутствующее поле возвращают null: вызывающий код сохраняет
 * прежние fallback'и по имени файла, каталогу и sidecar-изображениям.
 */
class DesktopAudioMetadataReader(
    private val logger: YamLogger,
) {

    data class Metadata(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationMs: Long?,
        val trackNumber: Int?,
        val discNumber: Int?,
        val year: Int?,
    )

    fun readMetadata(
        file: File,
    ): Metadata? {
        val audioFile =
            readAudioFile(
                file =
                    file,
                logFailure =
                    true,
            )
                ?: return null

        val tag =
            audioFile.tag

        val durationMs =
            audioFile.audioHeader
                ?.trackLength
                ?.takeIf {
                    it > 0
                }
                ?.toLong()
                ?.times(
                    MILLIS_IN_SECOND,
                )

        return Metadata(
            title =
                tag.tagValue(
                    FieldKey.TITLE,
                ),
            artist =
                tag.tagValue(
                    FieldKey.ARTIST,
                ),
            album =
                tag.tagValue(
                    FieldKey.ALBUM,
                ),
            durationMs =
                durationMs,
            trackNumber =
                tag.tagNumber(
                    FieldKey.TRACK,
                ),
            discNumber =
                tag.tagNumber(
                    FieldKey.DISC_NO,
                ),
            year =
                tag.tagValue(
                    FieldKey.YEAR,
                )
                    ?.take(
                        YEAR_DIGITS,
                    )
                    ?.toIntOrNull(),
        )
    }

    /** Возвращает embedded artwork, затем sidecar-картинку из каталога. */
    fun readArtwork(
        file: File,
    ): ByteArray? =
        readEmbeddedArtwork(
            file,
        )
            ?: sidecarArtworkFiles(
                file,
            )
                .firstNotNullOfOrNull { artworkFile ->
                    artworkFile.validatedArtworkBytes()
                }

    private fun readEmbeddedArtwork(
        file: File,
    ): ByteArray? =
        runCatching {
            readAudioFile(
                file =
                    file,
                logFailure =
                    false,
            )
                ?.tag
                ?.firstArtwork
                ?.binaryData
        }.getOrNull()
            ?.takeIf { bytes ->
                bytes.isNotEmpty() &&
                        bytes.size <=
                        MAX_ARTWORK_BYTES &&
                        bytes.isSupportedImage()
            }

    private fun sidecarArtworkFiles(
        audioFile: File,
    ): List<File> {
        val directory =
            audioFile.parentFile
                ?: return emptyList()

        return listOf(
            File(
                directory,
                "${audioFile.nameWithoutExtension}.jpg",
            ),
            File(
                directory,
                "${audioFile.nameWithoutExtension}.png",
            ),
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
        ).filter(
            File::isFile,
        )
    }

    private fun File.validatedArtworkBytes(): ByteArray? {
        if (
            length() !in
            1L..MAX_ARTWORK_BYTES.toLong()
        ) {
            return null
        }

        return runCatching {
            readBytes()
        }.getOrNull()
            ?.takeIf { bytes ->
                bytes.isSupportedImage()
            }
    }

    private fun readAudioFile(
        file: File,
        logFailure: Boolean,
    ): AudioFile? {
        if (
            file.extension
                .lowercase() !in
            TAGGED_EXTENSIONS
        ) {
            return null
        }

        return try {
            AudioFileIO.read(
                file,
            )
        } catch (_: Exception) {
            if (logFailure) {
                logger.warning(
                    TAG,
                    "[readMetadata] Не удалось прочитать теги: file=${file.name}",
                )
            }

            null
        }
    }

    private fun org.jaudiotagger.tag.Tag?.tagValue(
        key: FieldKey,
    ): String? =
        this
            ?.runCatching {
                getFirst(
                    key,
                )
            }
            ?.getOrNull()
            ?.trim()
            ?.takeIf(String::isNotBlank)

    private fun org.jaudiotagger.tag.Tag?.tagNumber(
        key: FieldKey,
    ): Int? =
        tagValue(
            key,
        )
            ?.substringBefore(
                '/',
            )
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf {
                it > 0
            }

    private fun ByteArray.isSupportedImage(): Boolean =
        isJpeg() ||
                isPng()

    private fun ByteArray.isJpeg(): Boolean =
        size >= 3 &&
                this[0] ==
                0xFF.toByte() &&
                this[1] ==
                0xD8.toByte() &&
                this[2] ==
                0xFF.toByte()

    private fun ByteArray.isPng(): Boolean =
        size >= 8 &&
                this[0] ==
                0x89.toByte() &&
                this[1] ==
                0x50.toByte() &&
                this[2] ==
                0x4E.toByte() &&
                this[3] ==
                0x47.toByte()

    private companion object {
        const val TAG =
            "DesktopAudioMetadata"

        const val MILLIS_IN_SECOND =
            1_000L

        const val YEAR_DIGITS =
            4

        const val MAX_ARTWORK_BYTES =
            20 * 1024 * 1024

        val TAGGED_EXTENSIONS =
            setOf(
                "mp3",
                "m4a",
                "wav",
                "aif",
                "aiff",
            )
    }
}
