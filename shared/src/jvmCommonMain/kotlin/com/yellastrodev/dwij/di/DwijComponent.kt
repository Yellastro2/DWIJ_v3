package com.yellastrodev.dwij.di

import com.yellastrodev.dwij.CacheManager
import com.yellastrodev.dwij.MusicSourceSelectionStore
import com.yellastrodev.dwij.MusicSourceSettings
import com.yellastrodev.dwij.auth.YandexSessionManager
import com.yellastrodev.dwij.auth.YandexSessionStore
import com.yellastrodev.dwij.auth.YandexAuthorizationRequiredNotifier
import com.yellastrodev.dwij.data.cache.FileCacheStore
import com.yellastrodev.dwij.data.cache.VkAudioFileCache
import com.yellastrodev.dwij.data.cache.VkLocalStorage
import com.yellastrodev.dwij.data.repo.LocalTrackDownloadProgress
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.db.DwijDatabase
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.repo.CoverRepository
import com.yellastrodev.dwij.data.repo.CatalogRepository
import com.yellastrodev.dwij.data.repo.LocalCatalogResolver
import com.yellastrodev.dwij.data.repo.LocalCatalogSynchronizer
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
import com.yellastrodev.dwij.data.source.CatalogRemoteSource
import com.yellastrodev.dwij.data.source.PlaybackRemoteSource
import com.yellastrodev.dwij.data.source.PlaylistCacheSource
import com.yellastrodev.dwij.data.source.PlaylistRemoteSource
import com.yellastrodev.dwij.data.source.SearchRemoteSource
import com.yellastrodev.dwij.data.source.TrackRemoteSource
import com.yellastrodev.dwij.data.source.WaveRemoteSource
import com.yellastrodev.dwij.playback.PlaybackSettings
import com.yellastrodev.dwij.playback.DailyPlaylistPlayback
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import com.yellastrodev.dwij.playback.HttpMediaRemote
import com.yellastrodev.dwij.playback.HttpMediaServiceAdvertiser
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Общий JVM-граф приложения.
 * Радиальное меню получает единое хранилище назначений для главной и экрана просмотра.
 *
 * Создаёт YamApiClient, восстанавливает отдельные сессии Яндекс/VK и собирает общие
 * репозитории, временное воспроизведение рекомендаций, HTTP-пульт с DNS-SD и постоянные настройки.
 * Подключает аудиокеш VK к общему лимиту хранения при старте.
 * Постоянные VK-bundle используют отдельный каталог и общую платформенную очередь загрузки.
 * Общий SongRepository передаётся VK-репозиторию напрямую; обратной зависимости нет.
 * Загрузка VK-обложек учитывает диагностический режим текущей OAuth или web-сессии.
 * Скан и автослияние мультисурсов используют постоянные настройки; слияние обновляет очередь на Main.
 * Desktop передаёт режим MP3 HLS для совместимости VK с JavaFX.
 *
 * Платформа передаёт системные реализации, низкоуровневое key-value хранилище
 * обычных настроек и защищённое хранилище авторизации.
 */
class DwijComponent private constructor(
    private val applicationScope: CoroutineScope,
    val logger: YamLogger,
    val yandexSessionManager: YandexSessionManager,
    val vkMusicRepository: com.yellastrodev.dwij.data.repo.VkMusicRepository,
    val songRepository: SongRepository,
    val cacheSettings: CacheSettings,
    val yandexProxySettings: YandexProxySettings,
    private val localKeyValueStore: LocalKeyValueStore,
    private val httpMediaServiceAdvertiser: HttpMediaServiceAdvertiser,
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
                yandexAuthorizationRequiredNotifier::notifyRequired,
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
            vkRequestsEnabled = { !vkMusicRepository.authorizationOnly },
        )
    }

    val catalogRepository: CatalogRepository by lazy {
        CatalogRepository(
            local = db.catalogDao(),
            remote = CatalogRemoteSource(yamClient),
            trackRepository = trackRepository,
            songRepository = songRepository,
            logger = logger,
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
            resolveVkUri = vkMusicRepository::playbackUri,
            settings = playbackSettings,
            scope = applicationScope,
            isTrackCached =
                trackCacheRepo::isCached,
            prefetchTrack =
                trackCacheRepo::prefetch,
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

    /** Общие сохранённые назначения пяти секторов радиального меню. */
    val radialMenuSettingsStore by lazy {
        com.yellastrodev.dwij.storage.RadialMenuSettingsStore(localKeyValueStore)
    }

    /** Временный плейлист дня: прямой API и очередь, без репозиториев фонотеки. */
    val dailyPlaylistPlayback: DailyPlaylistPlayback by lazy {
        DailyPlaylistPlayback(
            client = yamClient,
            player = playerRepo,
            scope = applicationScope,
            isWaveLoading = { waveRepository.isLoading.value },
            stopWave = waveRepository::stopObserving,
            isTrackCached = trackCacheRepo::isCached,
            onAuthorizationRequired = ::requireYandexAuthorization,
            logger = logger,
        )
    }

    /** Конечная общая подборка с чередованием плейлиста дня ЯМ и рекомендаций VK. */
    val mixedRecommendationPlayback by lazy {
        com.yellastrodev.dwij.playback.MixedRecommendationPlayback(
            dailyPlaylistPlayback, vkMusicRepository, trackRepository, songRepository, playerRepo,
            waveRepository::stopObserving, { waveRepository.isLoading.value }, trackCacheRepo::isCached, logger,
        )
    }

    /** Общий для Android и desktop HTTP-пульт текущего экземпляра плеера. */
    val httpMediaRemote: HttpMediaRemote by lazy {
        HttpMediaRemote(localKeyValueStore, playerRepo, applicationScope, httpMediaServiceAdvertiser)
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

    /** Готовый pipeline локального online-resolve; планировщик к нему пока не подключён. */
    val localCatalogSynchronizer: LocalCatalogSynchronizer by lazy {
        LocalCatalogSynchronizer(
            resolver = LocalCatalogResolver(searchRepository),
            database = db,
            localDao = db.localLibraryDao(),
            catalogDao = db.catalogDao(),
            songRepository = songRepository,
        )
    }

    val songMatchRepository:
            SongMatchRepository by lazy {

        SongMatchRepository(
            songDao = db.songDao(),
            matchDao = db.songMatchDao(),
            logger = logger,
            settings = localKeyValueStore,
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
     * Подключает VK-кеш/bundle, импортирует отсутствующую метадату и отменяет неактивные чтения relay.
     * Запускает скан/автослияние; завершённые объединения обновляют очередь без смены воспроизведения.
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

        vkMusicRepository.useAudioCache(VkAudioFileCache(FileCacheStore(
            File(trackCacheDirectory, "vk-audio"), cacheManager,
        ), logger))
        vkMusicRepository.useLocalStorage(VkLocalStorage(File(localYandexTrackDirectory.parentFile, "vk-local-tracks")))
        httpMediaRemote.startIfEnabled()
        applicationScope.coroutineContext[Job]?.invokeOnCompletion {
            httpMediaRemote.close()
            vkMusicRepository.close()
        }
        applicationScope.launch {
            playerRepo.currentPlaybackTrack.collect {
                vkMusicRepository.releaseInactivePlayback(playerRepo)
            }
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
                vkMusicRepository.indexSavedTracks()

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
            ) { sourceSongIds, mergedSongId ->
                songRepository.songsByIds(listOf(mergedSongId)).firstOrNull()?.let { mergedSong ->
                    withContext(Dispatchers.Main) {
                        playerRepository.applyMergedSong(sourceSongIds, mergedSong)
                    }
                }
            }
        }
    }

    /** Диспетчеризует платформенную загрузку по source-id, сохраняя прежний путь Яндекса. */
    suspend fun saveTrackLocally(trackId: String,
        onProgress: (LocalTrackDownloadProgress) -> Unit = {}): DataResult<File> =
        if (trackId.startsWith("vk:")) vkMusicRepository.saveLocally(trackId, onProgress)
        else trackCacheRepo.saveLocally(trackId, onProgress)

    /** Проверяет постоянное хранение по source-id общей очереди загрузок. */
    fun isTrackSavedLocally(trackId: String): Boolean =
        if (trackId.startsWith("vk:")) vkMusicRepository.isSavedLocally(trackId)
        else trackCacheRepo.isSavedLocally(trackId)

    companion object {

        private const val TAG =
            "DwijComponent"

        private const val PLAYLIST_MEMORY_CACHE_SIZE =
            50

        /**
         * Восстанавливает постоянные настройки и независимые защищённые Яндекс/VK-сессии,
         * затем создаёт общий индекс Song и передаёт его source-репозиториям без циклических зависимостей.
         * vkMp3HlsSegments включается desktop-хостом; Android сохраняет прежний TS-поток.
         */
        fun create(
            applicationScope: CoroutineScope,
            logger: YamLogger,
            localKeyValueStore: LocalKeyValueStore,
            httpMediaServiceAdvertiser: HttpMediaServiceAdvertiser,
            yandexSessionStore: YandexSessionStore,
            vkSessionPayloadStore: com.yellastrodev.dwij.storage.ProtectedSessionPayloadStore,
            db: DwijDatabase,
            trackCacheDirectory: File,
            localYandexTrackDirectory: File,
            coverCacheDirectory: File,
            playerEngine: PlayerEngine,
            localMediaSource: LocalMediaSource,
            canReadAudio: () -> Boolean,
            platformLifecycle: DwijPlatformLifecycle =
                NoOpDwijPlatformLifecycle,
            vkMp3HlsSegments: Boolean = false,
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

            val songRepository = SongRepository(
                songDao = db.songDao(), matchDao = db.songMatchDao(),
                yandexTrackDao = db.dTrackDao(), localTrackDao = db.localLibraryDao(),
                catalogDao = db.catalogDao(), vkTrackDao = db.vkLibraryDao(),
            )
            return DwijComponent(
                songRepository = songRepository,
                applicationScope =
                    applicationScope,
                logger =
                    logger,
                yandexSessionManager =
                    sessionManager,
                vkMusicRepository = runBlocking(Dispatchers.IO) {
                    com.yellastrodev.dwij.data.repo.VkMusicRepository(vkSessionPayloadStore, logger,
                        songRepository, db.vkLibraryDao(), mp3HlsSegments = vkMp3HlsSegments)
                        .also { it.restore() }
                },
                cacheSettings =
                    cacheSettings,
                yandexProxySettings =
                    yandexProxySettings,
                localKeyValueStore =
                    localKeyValueStore,
                httpMediaServiceAdvertiser =
                    httpMediaServiceAdvertiser,
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
