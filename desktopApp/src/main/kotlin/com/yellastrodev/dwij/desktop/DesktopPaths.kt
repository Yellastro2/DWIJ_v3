package com.yellastrodev.dwij.desktop

import java.io.File

/**
 * Все файловые каталоги Windows-порта.
 *
 * Пользовательские данные и кэш не смешиваются с каталогом установки приложения.
 */
data class DesktopPaths(
    val appDataDirectory: File,
    val cacheDirectory: File,
    val databaseFile: File,
    val settingsFile: File,
    val sessionFile: File,
    val trackCacheDirectory: File,
    val coverCacheDirectory: File,
    val musicDirectories: List<File>,
    val playlistExportDirectory: File,
) {
    companion object {
        /**
         * Создаёт стандартную Windows-разметку каталогов.
         *
         * DWIJ_MUSIC_DIRS можно использовать для явного задания одной или
         * нескольких музыкальных папок через системный path separator.
         */
        fun create(): DesktopPaths {
            val userHome = File(
                System.getProperty("user.home"),
            )

            val roamingRoot = System.getenv("APPDATA")
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?: File(
                    userHome,
                    "AppData/Roaming",
                )

            val localRoot = System.getenv("LOCALAPPDATA")
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?: File(
                    userHome,
                    "AppData/Local",
                )

            val appDataDirectory = File(
                roamingRoot,
                "DWIJ",
            ).apply {
                mkdirs()
            }

            val cacheDirectory = File(
                localRoot,
                "DWIJ/cache",
            ).apply {
                mkdirs()
            }

            val musicDirectories =
                configuredMusicDirectories()
                    .ifEmpty {
                        listOf(
                            File(
                                userHome,
                                "Music",
                            ),
                        )
                    }
                    .map(File::getAbsoluteFile)
                    .distinctBy { directory ->
                        directory.path.lowercase()
                    }
                    .filter(File::isDirectory)

            val playlistExportDirectory =
                File(
                    musicDirectories.firstOrNull()
                        ?: appDataDirectory,
                    "DWIJ Playlists",
                ).apply {
                    mkdirs()
                }

            return DesktopPaths(
                appDataDirectory =
                    appDataDirectory,
                cacheDirectory =
                    cacheDirectory,
                databaseFile =
                    File(
                        appDataDirectory,
                        "dwij.db",
                    ),
                settingsFile =
                    File(
                        appDataDirectory,
                        "settings.properties",
                    ),
                sessionFile =
                    File(
                        appDataDirectory,
                        "yandex-session.bin",
                    ),
                trackCacheDirectory =
                    File(
                        cacheDirectory,
                        "tracks",
                    ).apply {
                        mkdirs()
                    },
                coverCacheDirectory =
                    File(
                        cacheDirectory,
                        "covers",
                    ).apply {
                        mkdirs()
                    },
                musicDirectories =
                    musicDirectories,
                playlistExportDirectory =
                    playlistExportDirectory,
            )
        }

        private fun configuredMusicDirectories(): List<File> =
            System.getenv(
                "DWIJ_MUSIC_DIRS",
            )
                ?.split(
                    File.pathSeparatorChar,
                )
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                ?.map(::File)
                .orEmpty()
    }
}
