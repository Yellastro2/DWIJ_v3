package com.yellastrodev.dwij.di

import com.yellastrodev.dwij.CacheManager
import com.yellastrodev.dwij.MusicSourceSelectionStore
import com.yellastrodev.dwij.MusicSourceSettings
import com.yellastrodev.dwij.auth.YandexSessionManager
import com.yellastrodev.dwij.auth.YandexSessionStore
import com.yellastrodev.dwij.auth.YandexAuthorizationRequiredNotifier
import com.yellastrodev.dwij.data.cache.FileCacheStore
import com.yellastrodev.dwij.data.db.DwijDatabase
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.repo.CoverRepository
import com.yellastrodev.dwij.data.repo.LocalMusicRepository
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.data.repo.PlaylistRepository
import com.yellastrodev.dwij.data.repo.SearchRepository
import com.yellastrodev.dwij.data.repo.SongMatchRepository
import com.yellastrodev.dwij.data.repo.SongRepository
import com.yellastrodev.dwij.data.repo.TrackCacheRepository
import com.yellastrodev.dwij.data.repo.TrackRepository
import com.yellastrodev.dwij.data.repo.WaveRepository
import com.yellastrodev.dwij.data.source.LocalMediaSource
import com.yellastrodev.dwij.data.source.PlaybackRemoteSource
import com.yellastrodev.dwij.data.source.PlaylistCacheSource
import com.yellastrodev.dwij.data.source.PlaylistRemoteSource
import com.yellastrodev.dwij.data.source.SearchRemoteSource
import com.yellastrodev.dwij.data.source.TrackRemoteSource
import com.yellastrodev.dwij.data.source.WaveRemoteSource
import com.yellastrodev.dwij.playback.PlaybackSettings
import com.yellastrodev.dwij.playback.PlayerEngine
import com.yellastrodev.dwij.playback.TrackCoverLoader
import com.yellastrodev.dwij.playback.feedback.PlaybackFeedbackTracker
import com.yellastrodev.dwij.storage.CacheSettings
import com.yellastrodev.dwij.storage.LocalKeyValueStore
import com.yellastrodev.dwij.storage.StoredCacheSettings
import com.yellastrodev.dwij.storage.StoredMusicSourceSettings
import com.yellastrodev.dwij.storage.StoredPlaybackSettings
import com.yellastrodev.dwij.storage.YandexProxySettings
import com.yellastrodev.dwij.utils.DwLruCache
import com.yellastrodev.yamusicsdk.YamLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Общий JVM-граф приложения.
 *
 * Создаёт YamApiClient, восстанавливает сессию и собирает все общие
 * репозитории и постоянные настройки.
 *
 * Платформа передаёт системные реализации, низкоуровневое key-value хранилище
 * обычных настроек и защищённое хранилище авторизации.
 */
class DwijComponent private constructor(
    private val applicationScope: CoroutineScope,
    val logger: YamLogger,
    val yandexSessionManager: YandexSessionManager,
    val cacheSettings: CacheSettings,
    val yandexProxySettings: YandexProxySettings,
    private val db: DwijDatabase,
    private val trackCacheDirectory: File,
    private val localYandexTrackDirectory: File,
    private val coverCacheDirectory: File,
    private val playbackSettings: PlaybackSettings,
    private val playerEngine: PlayerEngine,
    private val musicSourceSettings: MusicSourceSettings,
    private val localMediaSource: LocalMediaSource,
    private val canReadAudio: () -> Boolean,
    private val platformLifecycle: DwijPlatformLifecycle,
) {

    val yandexAuthorizationRequiredNotifier =
        YandexAuthorizationRequiredNotifier()

    private val started =
        AtomicBoolean(false)

    private val yamClient =
        yandexSessionManager.client

    /** Сбрасывает непринятую сессию и просит корень приложения предложить повторный вход. */
    fun requireYandexAuthorization() {
        yandexSessionManager.clear()
        yandexAuthorizationRequiredNotifier.notifyRequired()
    }

    val songRepository: SongRepository by lazy {
        SongRepository(
            songDao = db.songDao(),
            matchDao = db.songMatchDao(),
            yandexTrackDao = db.dTrackDao(),
            localTrackDao = db.localLibraryDao(),
        )
    }

    val trackRepository: TrackRepository by lazy {
        TrackRepository(
            remote =
                TrackRemoteSource(
                    yamClient,
                ),
            local = db.dTrackDao(),
            songRepository = songRepository,
            scope = applicationScope,
            logger = logger,
        )
    }

    val playlistRepository: PlaylistRepository by lazy {
        val memoryCache =
            object :
                DwLruCache<Int, dYaPlaylist>(
                    PLAYLIST_MEMORY_CACHE_SIZE,
                ) {

                override fun sizeOf(
                    key: Int,
                    value: dYaPlaylist,
                ): Int = 1
            }

        PlaylistRepository(
            local = db.dPlaylistDao(),
            remote =
                PlaylistRemoteSource(
                    client = yamClient,
                    logger = logger,
                ),
            cache =
                PlaylistCacheSource(
                    memoryCache,
                ),
            scope = applicationScope,
            trackRepo = trackRepository,
            logger = logger,
        )
    }

    val cacheManager: CacheManager by lazy {
        CacheManager(
            trackDir = trackCacheDirectory,
            coverDir = coverCacheDirectory,
            maxCacheSizeBytes = {
                cacheSettings.maxSizeBytes
            },
            logger = logger,
        )
    }

    val trackCacheRepo: TrackCacheRepository by lazy {
        TrackCacheRepository(
            cacheDir = trackCacheDirectory,
            persistentDir = localYandexTrackDirectory,
            trackRepo = trackRepository,
            cacheManager = cacheManager,
            logger = logger,
            onAuthorizationRequired =
                ::requireYandexAuthorization,
        )
    }

    val coverFileCache: FileCacheStore by lazy {
        FileCacheStore(
            directory = coverCacheDirectory,
            cacheManager = cacheManager,
        )
    }

    val coverRepository: CoverRepository by lazy {
        CoverRepository(
            yamClient = yamClient,
            fileCache = coverFileCache,
        )
    }

    val trackCoverLoader: TrackCoverLoader by lazy {
        TrackCoverLoader(
            trackRepository = trackRepository,
            coverRepository = coverRepository,
            logger = logger,
        )
    }

    val musicSourceSelectionStore:
            MusicSourceSelectionStore by lazy {

        MusicSourceSelectionStore(
            settings = musicSourceSettings,
        )
    }

    val playerRepo: PlayerRepository by lazy {
        PlayerRepository(
            engine = playerEngine,
            settings = playbackSettings,
            scope = applicationScope,
            isTrackCached =
                trackCacheRepo::isCached,
            continueWave = { tracklist ->
                waveRepository.playWave(
                    tracklist,
                )
            },
            logger = logger,
        )
    }

    val waveRemoteSource:
            WaveRemoteSource by lazy {

        WaveRemoteSource(
            client = yamClient,
            logger = logger,
        )
    }

    val waveRepository: WaveRepository by lazy {
        WaveRepository(
            remote = waveRemoteSource,
            trackRepository = trackRepository,
            songRepository = songRepository,
            playerRepository = playerRepo,
            isTrackCached =
                trackCacheRepo::isCached,
            scope = applicationScope,
            logger = logger,
            onAuthorizationRequired =
                ::requireYandexAuthorization,
        )
    }

    val searchRepository:
            SearchRepository by lazy {

        SearchRepository(
            remote =
                SearchRemoteSource(
                    yamClient,
                ),
            logger = logger,
        )
    }

    val songMatchRepository:
            SongMatchRepository by lazy {

        SongMatchRepository(
            songDao = db.songDao(),
            matchDao = db.songMatchDao(),
            logger = logger,
        )
    }

    val localMusicRepository:
            LocalMusicRepository by lazy {

        LocalMusicRepository(
            dao = db.localLibraryDao(),
            mediaStore = localMediaSource,
            songRepository = songRepository,
            database = db,
            canReadAudio = canReadAudio,
            logger = logger,
        )
    }

    private val playbackRemoteSource:
            PlaybackRemoteSource by lazy {

        PlaybackRemoteSource(
            client = yamClient,
            logger = logger,
        )
    }

    val playbackFeedbackTracker:
            PlaybackFeedbackTracker by lazy {

        PlaybackFeedbackTracker(
            remote = playbackRemoteSource,
            scope = applicationScope,
            isTrackCached =
                trackCacheRepo::isCached,
            logger = logger,
        )
    }

    /**
     * Запускает общую и платформенную инициализацию ровно один раз.
     */
    fun start() {
        if (
            !started.compareAndSet(
                false,
                true,
            )
        ) {
            return
        }

        try {
            platformLifecycle
                .startLocalLibraryIntegration(
                    repository =
                        localMusicRepository,
                    scope =
                        applicationScope,
                )
        } catch (error: Exception) {
            logger.error(
                TAG,
                "[start] Не удалось запустить платформенную интеграцию медиатеки",
                error,
            )
        }

        applicationScope.launch {
            try {
                songRepository
                    .indexExistingTracks()

                logger.debug(
                    TAG,
                    "[start] Индекс песен актуализирован",
                )
            } catch (
                error: CancellationException,
            ) {
                throw error
            } catch (error: Exception) {
                logger.error(
                    TAG,
                    "[start] Не удалось актуализировать индекс песен",
                    error,
                )
            }

            songMatchRepository.start(
                applicationScope,
            )
        }
    }

    companion object {

        private const val TAG =
            "DwijComponent"

        private const val PLAYLIST_MEMORY_CACHE_SIZE =
            50

        /**
         * Восстанавливает постоянные настройки и сессию,
         * затем создаёт общий граф приложения.
         */
        fun create(
            applicationScope: CoroutineScope,
            logger: YamLogger,
            localKeyValueStore: LocalKeyValueStore,
            yandexSessionStore: YandexSessionStore,
            db: DwijDatabase,
            trackCacheDirectory: File,
            localYandexTrackDirectory: File,
            coverCacheDirectory: File,
            playerEngine: PlayerEngine,
            localMediaSource: LocalMediaSource,
            canReadAudio: () -> Boolean,
            platformLifecycle: DwijPlatformLifecycle =
                NoOpDwijPlatformLifecycle,
        ): DwijComponent {

            val cacheSettings =
                StoredCacheSettings(
                    localKeyValueStore,
                )

            val playbackSettings =
                StoredPlaybackSettings(
                    localKeyValueStore,
                )

            val musicSourceSettings =
                StoredMusicSourceSettings(
                    localKeyValueStore,
                )

            val yandexProxySettings =
                YandexProxySettings(
                    localKeyValueStore,
                )

            val sessionManager =
                runBlocking(
                    Dispatchers.IO,
                ) {
                    YandexSessionManager.create(
                        store =
                            yandexSessionStore,
                        logger =
                            logger,
                        proxyConfig =
                            yandexProxySettings
                                .activeConfigOrNull(),
                    )
                }

            return DwijComponent(
                applicationScope =
                    applicationScope,
                logger =
                    logger,
                yandexSessionManager =
                    sessionManager,
                cacheSettings =
                    cacheSettings,
                yandexProxySettings =
                    yandexProxySettings,
                db =
                    db,
                trackCacheDirectory =
                    trackCacheDirectory,
                localYandexTrackDirectory =
                    localYandexTrackDirectory,
                coverCacheDirectory =
                    coverCacheDirectory,
                playbackSettings =
                    playbackSettings,
                playerEngine =
                    playerEngine,
                musicSourceSettings =
                    musicSourceSettings,
                localMediaSource =
                    localMediaSource,
                canReadAudio =
                    canReadAudio,
                platformLifecycle =
                    platformLifecycle,
            )
        }
    }
}
