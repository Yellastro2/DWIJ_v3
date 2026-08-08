package com.yellastrodev.dwij.desktop

import androidx.room.Room
import com.yellastrodev.dwij.data.db.DwijDatabase
import com.yellastrodev.dwij.data.db.buildDwijDatabase
import com.yellastrodev.dwij.desktop.data.source.DesktopLocalMediaSource
import com.yellastrodev.dwij.desktop.playback.DesktopPlayerEngine
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.playback.PlaybackUriResolver
import com.yellastrodev.dwij.playback.PlayerVolumeControl
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
                playerEngine =
                    playerEngine,
            )
        }
    }
}