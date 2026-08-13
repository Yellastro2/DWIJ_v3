package com.yellastrodev.dwij.desktop

import androidx.room.Room
import com.yellastrodev.dwij.data.db.DwijDatabase
import com.yellastrodev.dwij.data.db.buildDwijDatabase
import com.yellastrodev.dwij.desktop.data.source.DesktopAudioMetadataReader
import com.yellastrodev.dwij.desktop.data.source.DesktopLocalMediaSource
import com.yellastrodev.dwij.desktop.playback.DesktopMediaArtworkProvider
import com.yellastrodev.dwij.desktop.playback.DesktopPlayerEngine
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.playback.PlaybackUriResolver
import com.yellastrodev.dwij.playback.PlayerVolumeControl
import com.yellastrodev.dwij.storage.MigratingYandexSessionStore
import com.yellastrodev.dwij.storage.ProtectedYandexSessionStore
import com.yellastrodev.dwij.storage.StoredYandexSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.math.roundToLong

/**
 * Собранный Windows runtime вокруг общего DwijComponent.
 */
class DesktopRuntime private constructor(
    val component: DwijComponent,
    val applicationScope: CoroutineScope,
    val paths: DesktopPaths,
    val settingsStore: DesktopLocalKeyValueStore,
    val musicDirectoryStore: DesktopMusicDirectoryStore,
    val audioMetadataReader: DesktopAudioMetadataReader,
    private val playerEngine: DesktopPlayerEngine,
) {

    /**
     * Desktop-only управление громкостью DWIJ.
     */
    val playerVolumeControl:
            PlayerVolumeControl
        get() =
            playerEngine

    /**
     * Сохраняет desktop-only состояние и освобождает playback backend.
     */
    fun close() {
        settingsStore.edit {
            putLong(
                PLAYER_VOLUME_KEY,
                (
                        playerEngine.volume.value *
                                VOLUME_STORAGE_SCALE
                        ).roundToLong(),
            )
        }

        playerEngine.close()
    }

    companion object {

        private const val PLAYER_VOLUME_KEY =
            "desktop.player.volume"

        private const val VOLUME_STORAGE_SCALE =
            1000f

        private const val DEFAULT_PLAYER_VOLUME =
            1f

        /**
         * Собирает desktop-аналоги AndroidDwijComponentFactory/yApplication.
         */
        fun create():
                DesktopRuntime {

            val paths =
                DesktopPaths.create()

            val applicationScope =
                CoroutineScope(
                    SupervisorJob() +
                            Dispatchers.IO,
                )

            val logger =
                YamLoggerDesktop()

            val audioMetadataReader =
                DesktopAudioMetadataReader(
                    logger,
                )

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

            val musicDirectoryStore =
                DesktopMusicDirectoryStore(
                    settingsStore =
                        localKeyValueStore,
                    defaultDirectories =
                        paths.musicDirectories,
                )

            val yandexSessionStore =
                MigratingYandexSessionStore(
                    primary =
                        ProtectedYandexSessionStore(
                            WindowsDpapiSessionPayloadStore(
                                file =
                                    paths.sessionFile,
                                logger =
                                    logger,
                            ),
                        ),
                    legacy =
                        StoredYandexSessionStore(
                            localKeyValueStore,
                        ),
                    migrationState =
                        localKeyValueStore,
                    logger =
                        logger,
                )

            /*
             * Восстанавливаем громкость до создания PlayerEngine,
             * чтобы первый MediaPlayer сразу получил нужное значение.
             *
             * В settings.properties хранится 0..1000.
             */
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

            /*
             * PlayerEngine создаётся раньше DwijComponent.
             *
             * Его callbacks resolveUri/resolveArtworkFile вызываются
             * только после создания component, при реальном
             * воспроизведении трека.
             */
            lateinit var component:
                    DwijComponent

            /*
             * Используем уже существующие CoverRepository и CacheManager.
             *
             * Благодаря lazy они не потребуются до того,
             * как component будет полностью создан.
             */
            val artworkProvider by lazy {
                DesktopMediaArtworkProvider(
                    coverRepository =
                        component
                            .coverRepository,
                    coverCacheDirectory =
                        paths
                            .coverCacheDirectory,
                    cacheManager =
                        component
                            .cacheManager,
                    metadataReader =
                        audioMetadataReader,
                )
            }

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
                    resolveArtworkFile = { track ->
                        artworkProvider.resolve(
                            track,
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
                    yandexSessionStore =
                        yandexSessionStore,
                    db =
                        database,
                    trackCacheDirectory =
                        paths
                            .trackCacheDirectory,
                    localYandexTrackDirectory =
                        paths
                            .localYandexTrackDirectory,
                    coverCacheDirectory =
                        paths
                            .coverCacheDirectory,
                    playerEngine =
                        playerEngine,
                    localMediaSource =
                        DesktopLocalMediaSource(
                            musicDirectoryStore =
                                musicDirectoryStore,
                            playlistExportDirectory =
                                paths
                                    .playlistExportDirectory,
                            metadataReader =
                                audioMetadataReader,
                        ),
                    canReadAudio = {
                        true
                    },
                    platformLifecycle =
                        DesktopDwijPlatformLifecycle,
                )

            playerEngine.bindPlaybackFeedback(
                component.playbackFeedbackTracker,
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
                musicDirectoryStore =
                    musicDirectoryStore,
                audioMetadataReader =
                    audioMetadataReader,
                playerEngine =
                    playerEngine,
            )
        }
    }
}
