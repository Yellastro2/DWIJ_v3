package com.yellastrodev.dwij.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yellastrodev.dwij.HomeMusicSource
import com.yellastrodev.dwij.RadialMenuCollection
import com.yellastrodev.dwij.RadialMenuTarget
import com.yellastrodev.dwij.playback.playRadialMenuTarget
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.models.SearchModel
import com.yellastrodev.dwij.models.SearchResultItemUiModel
import com.yellastrodev.dwij.models.SearchTrackSource
import com.yellastrodev.dwij.data.entities.dYaLikeTracklist.Companion.KIND_LIKED
import com.yellastrodev.dwij.resources.Res
import com.yellastrodev.dwij.resources.home_player_unknown_artist
import com.yellastrodev.dwij.ui.HomeCompactPlayerUiState
import com.yellastrodev.dwij.ui.HomeScreen
import com.yellastrodev.dwij.ui.LocalRadialMenuSelection
import com.yellastrodev.dwij.data.entities.MusicSource
import com.yellastrodev.dwij.utils.TrackChangeDirection
import com.yellastrodev.dwij.ui.HomeScreenPlatform
import com.yellastrodev.dwij.ui.LocalYamLogger
import com.yellastrodev.dwij.ui.RadialMenu
import com.yellastrodev.dwij.ui.RadialMenuAnimationStyle
import com.yellastrodev.dwij.ui.search.SearchScreen
import com.yellastrodev.dwij.ui.toImageBitmapOrNull
import com.yellastrodev.yamusicsdk.entities.CoverSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * Shared-route главного экрана.
 * Поиск выбирает независимый VK-репозиторий либо существующий локальный/Яндекс-сценарий.
 * «Треки» открывает полную фонотеку выбранного источника, включая объединённую коллекцию VK.
 * «Рекомендации» VK открывает карточки с загрузкой составов по нажатию.
 * Каталог VK отдельно открывает личные треки и объединённую фонотеку.
 * «Всё сразу» запускает только общую подборку ЯМ/VK, остальные действия пока не подключены.
 * Сектор «Настроить» передаёт переход к экрану радиального меню владельцу навигации.
 * Пять секторов запускают сохранённые коллекции независимо от вкладки источника.
 *
 * Не зависит от Android Context, Activity Result API, WorkManager и Navigation.
 * Платформа передаёт разрешения, системный back-handler и действия переходов.
 * Поиск показывает статусы постоянного хранения Яндекс/VK и ставит выбранный source-id в общую очередь.
 */
@Composable
fun HomeRoute(
    component: DwijComponent,
    playerModel: PlayerModel,
    routePlatform: HomeRoutePlatform,
    screenPlatform: HomeScreenPlatform,
    onOpenSettings: () -> Unit,
    onOpenRadialMenuSettings: () -> Unit,
    onOpenSongMatches: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenWaves: () -> Unit,
    onOpenVkRecommendations: () -> Unit,
    onOpenYandexPlaylist: (playlistId: String) -> Unit,
    onOpenArtists: () -> Unit,
    onOpenAlbums: () -> Unit,
    onOpenLocalTracks: () -> Unit,
    onOpenYandexTracks: () -> Unit,
    onOpenVkTracks: () -> Unit,
    onOpenVkMyTracks: () -> Unit,
    onOpenCatalogObject: (type: String, externalId: Int) -> Unit,
    onOpenPlayer: () -> Unit,
    onRequestLocalTrackDownload: (trackId: String, title: String) -> Unit,
    onShareYandexUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selection = LocalRadialMenuSelection.current
    val logger = LocalYamLogger.current
    val coroutineScope = rememberCoroutineScope()
    val musicSourceSelectionStore = component.musicSourceSelectionStore
    val mixedLoading by component.mixedRecommendationPlayback.loading.collectAsState()
    var mixedError by remember { mutableStateOf<String?>(null) }
    val radialTargets by component.radialMenuSettingsStore.targets.collectAsState()
    val primaryTarget by component.radialMenuSettingsStore.primaryTarget.collectAsState()
    var radialStarting by remember { mutableStateOf(false) }
    var radialMessage by remember { mutableStateOf<String?>(null) }

    /** Проверяет локальный доступ и запускает одно назначение; ошибки оставляют прежнюю очередь. */
    fun startRadialTarget(target: RadialMenuTarget) {
        if (radialStarting || selection != null) return
        radialStarting = true
        radialMessage = "Загружаем список…"
        coroutineScope.launch {
            try {
                val localTarget = target == RadialMenuTarget.Collection(RadialMenuCollection.LOCAL_TRACKS) ||
                    (target is RadialMenuTarget.Playlist && target.source == MusicSource.LOCAL)
                if (localTarget &&
                    !routePlatform.hasLocalMusicAccess() && !routePlatform.requestLocalMusicAccess()
                ) {
                    radialMessage = "Для локальных треков разрешите доступ к музыке"
                    return@launch
                }
                when (val result = component.playRadialMenuTarget(target)) {
                    is com.yellastrodev.dwij.data.DataResult.Success -> {
                        radialMessage = null
                        onOpenPlayer()
                    }
                    is com.yellastrodev.dwij.data.DataResult.Failure -> {
                        if (result.error == com.yellastrodev.dwij.data.DataError.Unauthorized &&
                            (target is RadialMenuTarget.YandexWave ||
                                (target is RadialMenuTarget.Playlist && target.source == MusicSource.YANDEX) ||
                                (target is RadialMenuTarget.Collection && target.kind in listOf(
                                    RadialMenuCollection.YANDEX_DAILY, RadialMenuCollection.YANDEX_LIKED,
                                    RadialMenuCollection.YANDEX_TRACKS)))
                        ) component.requireYandexAuthorization()
                        radialMessage = (result.error as? com.yellastrodev.dwij.data.DataError.InvalidData)?.message
                            ?: if (result.error == com.yellastrodev.dwij.data.DataError.Unauthorized)
                                "Войдите в нужный музыкальный сервис в настройках"
                            else "Не удалось загрузить список. Повторите попытку"
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.error(TAG, "[startRadialTarget] Не удалось запустить назначение радиального меню", error)
                radialMessage = "Не удалось запустить список. Повторите попытку"
            } finally {
                radialStarting = false
            }
        }
    }

    val selectedSource by
        musicSourceSelectionStore.selectedSource.collectAsState()
    val yandexPlaylists by
        component.playlistRepository.playlists.collectAsState()
    val likedYandexPlaylistId = yandexPlaylists
        .firstOrNull { playlist -> playlist.kind == KIND_LIKED }
        ?.getdId()

    val searchModelFactory = remember(component) {
        SearchModel.Factory(
            repository = component.searchRepository,
            localMusicRepository = component.localMusicRepository,
            trackRepository = component.trackRepository,
            songRepository = component.songRepository,
            playerRepository = component.playerRepo,
            onAuthorizationRequired =
                component::requireYandexAuthorization,
            vkRepository = component.vkMusicRepository,
        )
    }

    val searchModel = viewModel<SearchModel>(
        factory = searchModelFactory,
    )

    val searchState by searchModel.state.collectAsState()
    val vkAuthorized by component.vkMusicRepository.authorized.collectAsState()
    LaunchedEffect(vkAuthorized) {
        if (selectedSource == HomeMusicSource.Vk) searchModel.retrySearch()
    }
    val localStorageRevision by
        component.trackCacheRepo.localStorageRevision.collectAsState()
    val localDownloads by component.trackCacheRepo.localDownloads.collectAsState()
    val vkLocalDownloads by component.vkMusicRepository.localDownloads.collectAsState()
    val vkLocalStorageRevision by component.vkMusicRepository.localStorageRevision.collectAsState()
    var savedSearchYandexTrackIds by remember {
        mutableStateOf(emptySet<String>())
    }

    LaunchedEffect(searchState.results, localStorageRevision, vkLocalStorageRevision) {
        val yandexTrackIds = searchState.results.mapNotNull { item ->
            when (val source = (item as? SearchResultItemUiModel.Track)?.source) {
                is SearchTrackSource.Yandex -> source.track.id
                is SearchTrackSource.Vk -> "vk:${source.track.fullId}"
                else -> null
            }
        }
        savedSearchYandexTrackIds = withContext(Dispatchers.IO) {
            yandexTrackIds
                .filter(component::isTrackSavedLocally)
                .toSet()
        }
    }
    val track by playerModel.track.collectAsState()
    val playbackTrack by playerModel.playbackTrack.collectAsState()
    val playerState by playerModel.playerState.collectAsState()

    var permissionRequestInFlight by remember {
        mutableStateOf(false)
    }

    var cover by remember(
        track?.id,
        playbackTrack?.instanceId,
    ) {
        mutableStateOf<ImageBitmap?>(null)
    }

    val unknownArtist = stringResource(
        Res.string.home_player_unknown_artist,
    )

    LaunchedEffect(
        musicSourceSelectionStore,
        routePlatform,
    ) {
        val restored = musicSourceSelectionStore.restore()

        if (
            restored == HomeMusicSource.Local &&
            !routePlatform.hasLocalMusicAccess()
        ) {
            musicSourceSelectionStore.select(
                HomeMusicSource.Yandex,
            )
        }
    }

    LaunchedEffect(selectedSource) {
        if (selectedSource != HomeMusicSource.All) searchModel.setSource(selectedSource)
    }

    LaunchedEffect(
        track?.id,
        playbackTrack?.instanceId,
    ) {
        cover = null

        track?.let { currentTrack ->
            playerModel
                .cover(currentTrack)
                .flowOn(Dispatchers.IO)
                .collect { imageBitmap ->
                    cover = imageBitmap
                }
        }
    }

    /** Переключает сетевой источник сразу, а локальный после проверки разрешения. */
    fun selectMusicSource(source: HomeMusicSource) {
        if (
            source == selectedSource ||
            permissionRequestInFlight
        ) {
            return
        }

        if (source != HomeMusicSource.Local) {
            musicSourceSelectionStore.select(source)
            return
        }

        if (routePlatform.hasLocalMusicAccess()) {
            musicSourceSelectionStore.select(source)
            routePlatform.startLocalLibrarySync()
            return
        }

        permissionRequestInFlight = true
        musicSourceSelectionStore.preview(HomeMusicSource.Local)

        coroutineScope.launch {
            val granted = try {
                routePlatform.requestLocalMusicAccess()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.error(
                    TAG,
                    "[selectMusicSource] Не удалось запросить доступ к локальной музыке",
                    error,
                )
                false
            } finally {
                permissionRequestInFlight = false
            }

            if (granted) {
                musicSourceSelectionStore.select(HomeMusicSource.Local)
                routePlatform.startLocalLibrarySync()
            } else {
                logger.warning(
                    TAG,
                    "[selectMusicSource] Доступ к локальной музыке не выдан",
                )
                musicSourceSelectionStore.select(HomeMusicSource.Yandex)
            }
        }
    }

    HomeScreen(
        onPrimaryStartClick = { startRadialTarget(primaryTarget) },
        radialTargets = radialTargets,
        onRadialTargetClick = ::startRadialTarget,
        radialPlaybackMessage = radialMessage,
        onRadialMenuSettingsClick = onOpenRadialMenuSettings,
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        onSettingsClick = onOpenSettings,
        onSongMatchesClick = onOpenSongMatches,
        onPlaylistsClick = onOpenPlaylists,
        onTracksClick = {
            when (selectedSource) {
                HomeMusicSource.Local -> onOpenLocalTracks()
                HomeMusicSource.Yandex -> onOpenYandexTracks()
                HomeMusicSource.Vk -> onOpenVkTracks()
                HomeMusicSource.All -> Unit
            }
        },
        onWaveClick = {
            if (selection != null) selection.onSelect(RadialMenuTarget.YandexWave("user:onyourwave", "Моя волна"))
            else {
                component.waveRepository.requestWave()
                onOpenPlayer()
            }
        },
        onWavesClick = {
            onOpenWaves()
        },
        onAllTracksClick = {},
        onVkRecommendationsClick = onOpenVkRecommendations,
        onVkMyTracksClick = onOpenVkMyTracks,
        mixedRecommendationLoading = mixedLoading,
        mixedRecommendationError = mixedError,
        onMixedRecommendationClick = {
            if (selection != null) selection.onSelect(RadialMenuTarget.Collection(RadialMenuCollection.MIXED_RECOMMENDATIONS))
            else if (!mixedLoading) {
                mixedError = null
                coroutineScope.launch {
                    when (val result = component.mixedRecommendationPlayback.play("Подборка")) {
                        is com.yellastrodev.dwij.data.DataResult.Success -> onOpenPlayer()
                        is com.yellastrodev.dwij.data.DataResult.Failure -> mixedError =
                            (result.error as? com.yellastrodev.dwij.data.DataError.InvalidData)?.message
                                ?: if (result.error == com.yellastrodev.dwij.data.DataError.Unauthorized)
                                    "Для подборки войдите в Яндекс Музыку и VK в настройках"
                                else "Не удалось загрузить подборку. Повторите попытку"
                    }
                }
            }
        },
        onArtistsClick = onOpenArtists,
        onAlbumsClick = onOpenAlbums,
        onLikedClick = {
            when {
                selectedSource != HomeMusicSource.Yandex -> false
                likedYandexPlaylistId == null -> false
                else -> {
                    onOpenYandexPlaylist(likedYandexPlaylistId)
                    true
                }
            }
        },
        onPlayerOpenClick = onOpenPlayer,
        onPlayerPlayPauseClick = playerModel::playAudio,
        onPlayerPreviousClick = {
            coroutineScope.launch {
                playerModel.prevTrack()
            }
        },
        onPlayerNextClick = {
            coroutineScope.launch {
                playerModel.nextTrack()
            }
        },
        player = track?.let { currentTrack ->
            HomeCompactPlayerUiState(
                title = currentTrack.title,
                artist = currentTrack.artistNames
                    .joinToString(", ")
                    .ifBlank { unknownArtist },
                cover = cover,
                isPlaying = playerState.wantsToPlay,
                currentPositionMillis = playerState.currentPosition,
                durationMillis = playerState.duration,
                isNextPending =
                    playerState.pendingTrackChange ==
                        TrackChangeDirection.NEXT,
            )
        },
        selectedSource = selectedSource,
        onSourceSelected = ::selectMusicSource,
        radialMenuContent = { state, radialModifier ->
            RadialMenu(
                items = state.items,
                visible = state.visible,
                onPrimaryClick = state.onPrimaryClick,
                onVisualActivation = state.onVisualActivation,
                onPressChange = state.onPressChange,
                onItemClick = state.onItemClick,
                onDismiss = state.onDismiss,
                outerRadiusFraction = state.outerRadiusFraction,
                animationStyle = RadialMenuAnimationStyle.GlitchFlicker,
                enabled = selection == null,
                modifier = radialModifier,
            )
        },
        searchContent = { searchModifier ->
            SearchScreen(
                onErrorAction = {
                    if (searchState.error == com.yellastrodev.dwij.data.DataError.Unauthorized) onOpenSettings()
                    else searchModel.retrySearch()
                },
                selectedSource = selectedSource,
                onSourceSelected = ::selectMusicSource,
                state = searchState,
                onQueryChange = searchModel::updateQuery,
                loadTrackCover = { item ->
                    withContext(Dispatchers.IO) {
                        when (val source = item.source) {
                            is SearchTrackSource.Vk -> component.coverRepository
                                .getVkTrackCover(source.track)?.toImageBitmapOrNull()
                            is SearchTrackSource.Yandex -> {
                                component.coverRepository
                                    .getTrackCover(
                                        track = source.track,
                                        size = CoverSize.`100x100`,
                                    )
                                    ?.toImageBitmapOrNull()
                            }

                            is SearchTrackSource.Local -> {
                                source.song.localInstances
                                    .firstOrNull()
                                    ?.let { instance ->
                                        playerModel
                                            .cover(
                                                instance = instance,
                                                maxEdgePx = SEARCH_COVER_SIZE_PX,
                                            )
                                            .first()
                                    }
                            }
                        }
                    }
                },
                loadEntityCover = { key, uri ->
                    withContext(Dispatchers.IO) {
                        component.coverRepository
                            .getRemoteCover(
                                entityType = SEARCH_ENTITY_TYPE,
                                entityId = key,
                                url = uri,
                                size = CoverSize.`100x100`,
                            )
                            ?.toImageBitmapOrNull()
                    }
                },
                onResultClick = { item ->
                    when (item) {
                        is SearchResultItemUiModel.Track -> {
                            if (selection == null) searchModel.playTrack(item, onStarted = onOpenPlayer)
                        }
                        is SearchResultItemUiModel.Entity -> {
                            val type = when (item.kind) {
                                com.yellastrodev.dwij.models.SearchEntityKind.Artist ->
                                    DwijDestination.OBJECT_TYPE_ARTIST
                                com.yellastrodev.dwij.models.SearchEntityKind.Album ->
                                    DwijDestination.OBJECT_TYPE_ALBUM
                            }
                            onOpenCatalogObject(type, item.externalId)
                        }
                    }
                },
                savedYandexTrackIds = savedSearchYandexTrackIds,
                savingYandexTrackIds = localDownloads.keys + vkLocalDownloads.keys,
                onRequestLocalTrackDownload = { id, title ->
                    searchState.results.filterIsInstance<SearchResultItemUiModel.Track>()
                        .mapNotNull { (it.source as? SearchTrackSource.Vk)?.track }
                        .firstOrNull { "vk:${it.fullId}" == id }?.let(component.vkMusicRepository::rememberDownloadAudio)
                    onRequestLocalTrackDownload(id, title)
                },
                onShareYandexTrack = { trackId ->
                    onShareYandexUrl(YandexMusicShareLinks.track(trackId))
                },
                modifier = searchModifier,
            )
        },
        platform = screenPlatform,
    )
}

private const val TAG = "HomeRoute"
private const val SEARCH_ENTITY_TYPE = "search"
private const val SEARCH_COVER_SIZE_PX = 100
