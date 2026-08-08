package com.yellastrodev.dwij.desktop

import androidx.room.Room
import com.yellastrodev.dwij.data.db.DwijDatabase
import com.yellastrodev.dwij.data.db.buildDwijDatabase
import com.yellastrodev.dwij.desktop.data.source.DesktopLocalMediaSource
import com.yellastrodev.dwij.desktop.playback.DesktopPlayerEngine
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.playback.PlaybackUriResolver
import com.yellastrodev.dwij.playback.PlayerVolumeControl
import com.yellastrodev.dwij.storage.LocalKeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Собранный Windows runtime вокруг общего DwijComponent.
 */
class DesktopRuntime private constructor(
    val component: DwijComponent,
    val applicationScope: CoroutineScope,
    val paths: DesktopPaths,
    val settingsStore: LocalKeyValueStore,
    private val playerEngine: DesktopPlayerEngine,
) {

    /**
     * Опциональная desktop-возможность управления громкостью приложения.
     *
     * Наружу отдаётся интерфейс, а не конкретный JavaFX backend.
     */
    val playerVolumeControl:
            PlayerVolumeControl
        get() =
            playerEngine

    /**
     * Освобождает платформенный audio backend.
     */
    fun close() {
        settingsStore.edit {
            putLong(
                PLAYER_VOLUME_KEY,
                (
                        playerEngine.volume.value *
                                VOLUME_STORAGE_SCALE
                        ).toLong(),
            )
        }

        playerEngine.close()
    }

    companion object {

        /**
         * Собирает desktop-аналоги AndroidDwijComponentFactory/yApplication.
         */
        fun create(): DesktopRuntime {
            val paths =
                DesktopPaths.create()

            val applicationScope =
                CoroutineScope(
                    SupervisorJob() +
                            Dispatchers.IO,
                )

            val logger =
                YamLoggerDesktop()

            val database =
                buildDwijDatabase(
                    Room.databaseBuilder<DwijDatabase>(
                        name =
                            paths.databaseFile
                                .absolutePath,
                    ),
                )

            val localKeyValueStore =
                DesktopLocalKeyValueStore(
                    paths.settingsFile,
                )

            val initialVolume =
                localKeyValueStore
                    .getLong(
                        PLAYER_VOLUME_KEY,
                    )
                    ?.toFloat()
                    ?.div(
                        VOLUME_STORAGE_SCALE,
                    )
                    ?.coerceIn(
                        0f,
                        1f,
                    )
                    ?: DEFAULT_PLAYER_VOLUME

            lateinit var component:
                    DwijComponent

            /*
             * Resolver требует TrackCacheRepository из уже собранного component.
             * Lambda будет вызвана только при первом реальном воспроизведении,
             * поэтому component к этому моменту уже присвоен.
             */
            val playerEngine =
                DesktopPlayerEngine(
                    scope =
                        applicationScope,
                    logger =
                        logger,
                    initialVolume =
                        initialVolume,
                    resolveUri = { uri ->
                        PlaybackUriResolver(
                            component
                                .trackCacheRepo,
                        ).resolve(
                            uri,
                        )
                    },
                )

            component =
                DwijComponent.create(
                    applicationScope =
                        applicationScope,
                    logger =
                        logger,
                    localKeyValueStore =
                        localKeyValueStore,
                    db =
                        database,
                    trackCacheDirectory =
                        paths
                            .trackCacheDirectory,
                    coverCacheDirectory =
                        paths
                            .coverCacheDirectory,
                    playerEngine =
                        playerEngine,
                    localMediaSource =
                        DesktopLocalMediaSource(
                            musicDirectories =
                                paths
                                    .musicDirectories,
                            playlistExportDirectory =
                                paths
                                    .playlistExportDirectory,
                        ),
                    canReadAudio = {
                        true
                    },
                    platformLifecycle =
                        DesktopDwijPlatformLifecycle,
                )

            component.start()

            return DesktopRuntime(
                component =
                    component,
                applicationScope =
                    applicationScope,
                paths =
                    paths,
                settingsStore =
                    localKeyValueStore,
                playerEngine =
                    playerEngine,
            )
        }

        private const val PLAYER_VOLUME_KEY =
            "desktop.player.volume"

        private const val VOLUME_STORAGE_SCALE =
            1000f

        private const val DEFAULT_PLAYER_VOLUME =
            1f
    }
}