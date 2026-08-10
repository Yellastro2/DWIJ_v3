package com.yellastrodev.dwij.desktop

import java.io.File

/**
 * Хранит выбранные пользователем каталоги локальной музыки.
 *
 * Пока пользователь не менял список, возвращает каталоги из DesktopPaths:
 * DWIJ_MUSIC_DIRS либо стандартный %USERPROFILE%\Music.
 */
class DesktopMusicDirectoryStore(
    private val settingsStore: DesktopLocalKeyValueStore,
    private val defaultDirectories: List<File>,
) {

    fun directories(): List<File> {
        val savedValue =
            settingsStore.getString(
                MUSIC_DIRECTORIES_KEY,
            )

        return if (savedValue == null) {
            normalize(
                defaultDirectories,
            )
        } else {
            normalize(
                savedValue
                    .lineSequence()
                    .filter(String::isNotBlank)
                    .map(::File)
                    .toList(),
            )
        }
    }

    fun replace(
        directories: List<File>,
    ): List<File> {
        val normalized =
            normalize(
                directories,
            )

        settingsStore.edit {
            putString(
                MUSIC_DIRECTORIES_KEY,
                normalized.joinToString(
                    separator = "\n",
                ) { directory ->
                    directory.absolutePath
                },
            )
        }

        return normalized
    }

    private fun normalize(
        directories: List<File>,
    ): List<File> =
        directories
            .map { directory ->
                directory
                    .absoluteFile
                    .toPath()
                    .normalize()
                    .toFile()
            }
            .distinctBy { directory ->
                directory.path.lowercase()
            }

    private companion object {
        const val MUSIC_DIRECTORIES_KEY =
            "desktop.music.directories"
    }
}
