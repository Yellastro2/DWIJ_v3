package com.yellastrodev.dwij.data.source

import com.yellastrodev.dwij.data.entities.LocalTrackEntity
import java.io.File

/**
 * Сопоставляет ссылку на файл из M3U с локальным треком.
 *
 * Проверяет content URI, абсолютный путь, путь относительно M3U-файла
 * и в последнюю очередь уникальное совпадение по имени файла.
 */
object M3uTrackMatcher {

    fun resolve(
        reference: String,
        playlistAbsolutePath: String?,
        tracks: List<LocalTrackEntity>,
    ): LocalTrackEntity? {
        tracks
            .firstOrNull { track ->
                track.contentUri == reference
            }
            ?.let { track ->
                return track
            }

        val normalizedReference =
            normalizePath(reference)

        tracks
            .firstOrNull { track ->
                normalizePath(track.absolutePath) ==
                        normalizedReference
            }
            ?.let { track ->
                return track
            }

        if (
            playlistAbsolutePath != null &&
            !File(reference).isAbsolute
        ) {
            val resolvedPath =
                runCatching {
                    File(
                        File(playlistAbsolutePath).parentFile,
                        reference,
                    ).canonicalPath
                }.getOrNull()

            val normalizedResolvedPath =
                normalizePath(resolvedPath)

            tracks
                .firstOrNull { track ->
                    normalizePath(track.absolutePath) ==
                            normalizedResolvedPath
                }
                ?.let { track ->
                    return track
                }
        }

        val referencedFileName =
            File(reference).name

        return tracks
            .filter { track ->
                track.displayName.equals(
                    referencedFileName,
                    ignoreCase = true,
                )
            }
            .singleOrNull()
    }

    private fun normalizePath(
        value: String?,
    ): String? = value
        ?.replace('\\', '/')
        ?.trim()
        ?.lowercase()
}