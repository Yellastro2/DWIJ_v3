package com.yellastrodev.dwij.desktop.data.source

import com.yellastrodev.dwij.data.entities.LocalPlaylistEntity
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntryEntity
import com.yellastrodev.dwij.data.entities.LocalTrackEntity
import com.yellastrodev.dwij.data.source.LocalMediaSnapshot
import com.yellastrodev.dwij.data.source.LocalMediaSource
import com.yellastrodev.dwij.data.source.M3uExportResult
import com.yellastrodev.dwij.desktop.DesktopMusicDirectoryStore
import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * Windows-аналог Android MediaStore.
 *
 * Сканирует музыкальные каталоги файловой системы, читает доступные теги и
 * сохраняет прежние fallback'и по имени файла и каталогу для файлов без тегов.
 */
class DesktopLocalMediaSource(
    private val musicDirectoryStore: DesktopMusicDirectoryStore,
    private val playlistExportDirectory: File,
    private val metadataReader: DesktopAudioMetadataReader,
) : LocalMediaSource {

    override fun currentGeneration(): String {
        val musicDirectories =
            musicDirectoryStore.directories()

        return generation(
            audioFiles(
                musicDirectories,
            ),
        )
    }

    override fun findChangedBackingFiles(
        tracks: List<LocalTrackEntity>,
    ): List<LocalTrackEntity> =
        tracks.filter { track ->
            val file =
                track.absolutePath
                    ?.let(::File)
                    ?: track.contentUri
                        .takeIf { uri ->
                            uri.startsWith(
                                FILE_URI_PREFIX,
                            )
                        }
                        ?.let { uri ->
                            runCatching {
                                File(
                                    URI(uri),
                                )
                            }.getOrNull()
                        }

            file == null ||
                !file.isFile ||
                file.length() !=
                    track.sizeBytes ||
                file.lastModified() / 1_000L !=
                    track.dateModifiedSeconds
        }

    override suspend fun rescanTracks(
        tracks: List<LocalTrackEntity>,
    ): Boolean =
        true

    override fun scan(): LocalMediaSnapshot {
        val musicDirectories =
            musicDirectoryStore.directories()

        val files =
            audioFiles(
                musicDirectories,
            )

        return LocalMediaSnapshot(
            tracks =
                files.map { file ->
                    toTrack(
                        file =
                            file,
                        musicDirectories =
                            musicDirectories,
                    )
                },
            /*
             * Импорт произвольных M3U оставляем на следующий проход.
             * Плейлисты, созданные самим DWIJ, сохраняются shared-репозиторием
             * и экспортируются через exportM3u().
             */
            playlists =
                emptyList<LocalPlaylistEntity>(),
            entries =
                emptyList<LocalPlaylistEntryEntity>(),
            generation =
                generation(files),
        )
    }

    override fun exportM3u(
        name: String,
        tracks: List<LocalTrackEntity>,
        existingUri: String?,
    ): M3uExportResult {
        playlistExportDirectory
            .mkdirs()

        val targetFile =
            existingUri
                ?.takeIf { uri ->
                    uri.startsWith(
                        FILE_URI_PREFIX,
                    )
                }
                ?.let { uri ->
                    runCatching {
                        File(
                            URI(uri),
                        )
                    }.getOrNull()
                }
                ?: File(
                    playlistExportDirectory,
                    "${safeFileName(name)}.m3u8",
                )

        targetFile.parentFile
            ?.mkdirs()

        val content =
            buildString {
                appendLine(
                    "#EXTM3U",
                )

                tracks.forEach { track ->
                    val path =
                        track.absolutePath
                            ?: runCatching {
                                File(
                                    URI(
                                        track.contentUri,
                                    ),
                                ).absolutePath
                            }.getOrNull()
                            ?: track.contentUri

                    appendLine(path)
                }
            }

        targetFile.writeText(
            content,
            Charsets.UTF_8,
        )

        return M3uExportResult(
            uri =
                targetFile.toURI()
                    .toString(),
            hash =
                sha256(
                    content.toByteArray(
                        Charsets.UTF_8,
                    ),
                ),
        )
    }

    private fun audioFiles(
        musicDirectories: List<File>,
    ): List<File> =
        musicDirectories
            .asSequence()
            .filter(File::isDirectory)
            .flatMap { directory ->
                directory
                    .walkTopDown()
                    .onEnter { child ->
                        !child.isHidden &&
                            child.name !in
                            IGNORED_DIRECTORY_NAMES
                    }
                    .filter(File::isFile)
                    .filter(::isSupportedAudio)
            }
            .map { file ->
                runCatching {
                    file.canonicalFile
                }.getOrElse {
                    file.absoluteFile
                }
            }
            .distinctBy { file ->
                file.path.lowercase()
            }
            .sortedBy { file ->
                file.path.lowercase()
            }
            .toList()

    private fun toTrack(
        file: File,
        musicDirectories: List<File>,
    ): LocalTrackEntity {
        val baseName =
            file.nameWithoutExtension
                .trim()

        val artistAndTitle =
            baseName.split(
                " - ",
                limit = 2,
            )

        val artist =
            artistAndTitle
                .takeIf { parts ->
                    parts.size == 2
                }
                ?.first()
                ?.trim()
                ?.takeIf(String::isNotBlank)

        val title =
            if (
                artistAndTitle.size == 2
            ) {
                artistAndTitle[1]
                    .trim()
                    .ifBlank {
                        baseName
                    }
            } else {
                baseName
            }

        val metadata =
            metadataReader.readMetadata(
                file,
            )

        val normalizedPath =
            file.absolutePath
                .replace(
                    '\\',
                    '/',
                )
                .lowercase()

        val idHash =
            sha256(
                normalizedPath.toByteArray(
                    Charsets.UTF_8,
                ),
            )

        return LocalTrackEntity(
            instanceId =
                "local:desktop:$idHash",
            mediaStoreId =
                normalizedPath
                    .hashCode()
                    .toLong()
                    .let { value ->
                        if (
                            value == Long.MIN_VALUE
                        ) {
                            0L
                        } else {
                            kotlin.math.abs(value)
                        }
                    },
            volumeName =
                "desktop",
            contentUri =
                file.toURI()
                    .toString(),
            displayName =
                file.name,
            title =
                metadata
                    ?.title
                    ?: title,
            artist =
                metadata
                    ?.artist
                    ?: artist,
            album =
                metadata
                    ?.album
                    ?: file.parentFile
                        ?.name,
            albumId =
                null,
            durationMs =
                metadata
                    ?.durationMs
                    ?: 0L,
            trackNumber =
                metadata
                    ?.trackNumber,
            discNumber =
                metadata
                    ?.discNumber,
            year =
                metadata
                    ?.year,
            mimeType =
                mimeType(file),
            sizeBytes =
                file.length(),
            dateModifiedSeconds =
                file.lastModified() /
                    1_000L,
            relativePath =
                relativePath(
                    file =
                        file,
                    musicDirectories =
                        musicDirectories,
                ),
            absolutePath =
                file.absolutePath,
        )
    }

    private fun relativePath(
        file: File,
        musicDirectories: List<File>,
    ): String? =
        musicDirectories
            .firstNotNullOfOrNull { root ->
                runCatching {
                    file.toPath()
                        .let { filePath ->
                            root.toPath()
                                .takeIf {
                                    filePath.startsWith(
                                        it,
                                    )
                                }
                                ?.relativize(
                                    filePath,
                                )
                                ?.parent
                                ?.toString()
                        }
                }.getOrNull()
            }

    private fun generation(
        files: List<File>,
    ): String {
        val payload =
            buildString {
                files.forEach { file ->
                    append(
                        file.absolutePath
                            .lowercase(),
                    )
                    append('|')
                    append(
                        file.length(),
                    )
                    append('|')
                    append(
                        file.lastModified(),
                    )
                    append('\n')
                }
            }

        return DESKTOP_GENERATION_PREFIX +
            sha256(
                payload.toByteArray(
                    Charsets.UTF_8,
                ),
            )
    }

    private fun isSupportedAudio(
        file: File,
    ): Boolean =
        file.extension
            .lowercase() in
            SUPPORTED_EXTENSIONS

    private fun mimeType(
        file: File,
    ): String? =
        when (
            file.extension
                .lowercase()
        ) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "aif",
            "aiff",
            -> "audio/aiff"

            else -> null
        }

    private fun safeFileName(
        value: String,
    ): String =
        value
            .trim()
            .ifBlank {
                "Playlist"
            }
            .replace(
                INVALID_FILE_CHARS,
                "_",
            )

    private fun sha256(
        bytes: ByteArray,
    ): String =
        MessageDigest
            .getInstance(
                "SHA-256",
            )
            .digest(bytes)
            .joinToString(
                separator = "",
            ) { byte ->
                "%02x".format(
                    byte,
                )
            }
            .take(
                HASH_LENGTH,
            )

    private companion object {
        const val FILE_URI_PREFIX =
            "file:"

        const val HASH_LENGTH =
            24

        const val DESKTOP_GENERATION_PREFIX =
            "desktop:metadata-v2:"

        val SUPPORTED_EXTENSIONS =
            setOf(
                "mp3",
                "m4a",
                "aac",
                "wav",
                "aif",
                "aiff",
            )

        val IGNORED_DIRECTORY_NAMES =
            setOf(
                ".git",
                ".gradle",
                "build",
            )

        val INVALID_FILE_CHARS =
            Regex(
                """[\\/:*?"<>|]""",
            )
    }
}
