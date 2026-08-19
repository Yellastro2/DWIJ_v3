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
 *
 * Не зависит от Android Context, Activity Result API, WorkManager и Navigation.
 * Платформа передаёт разрешения, системный back-handler и действия переходов.
 */
@Composable
fun HomeRoute(
    component: DwijComponent,
    playerModel: PlayerModel,
    routePlatform: HomeRoutePlatform,
    screenPlatform: HomeScreenPlatform,
    onOpenSettings: () -> Unit,
    onOpenSongMatches: () -> Unit,
    onOpenPlaylists: () -> Unit,
    onOpenYandexPlaylist: (playlistId: String) -> Unit,
    onOpenArtists: () -> Unit,
    onOpenAlbums: () -> Unit,
    onOpenLocalTracks: () -> Unit,
    onOpenYandexTracks: () -> Unit,
    onOpenCatalogObject: (type: String, externalId: Int) -> Unit,
    onOpenPlayer: () -> Unit,
    onRequestLocalTrackDownload: (trackId: String, title: String) -> Unit,
    onShareYandexUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val logger = LocalYamLogger.current
    val coroutineScope = rememberCoroutineScope()
    val musicSourceSelectionStore = component.musicSourceSelectionStore

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
        )
    }

    val searchModel = viewModel<SearchModel>(
        factory = searchModelFactory,
    )

    val searchState by searchModel.state.collectAsState()
    val localStorageRevision by
        component.trackCacheRepo.localStorageRevision.collectAsState()
    val localDownloads by component.trackCacheRepo.localDownloads.collectAsState()
    var savedSearchYandexTrackIds by remember {
        mutableStateOf(emptySet<String>())
    }

    LaunchedEffect(searchState.results, localStorageRevision) {
        val yandexTrackIds = searchState.results.mapNotNull { item ->
            ((item as? SearchResultItemUiModel.Track)?.source as? SearchTrackSource.Yandex)
                ?.track
                ?.id
        }
        savedSearchYandexTrackIds = withContext(Dispatchers.IO) {
            yandexTrackIds
                .filter(component.trackCacheRepo::isSavedLocally)
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
        searchModel.setYandexEnabled(
            selectedSource == HomeMusicSource.Yandex,
        )
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

    fun selectMusicSource(source: HomeMusicSource) {
        if (
            source == selectedSource ||
            permissionRequestInFlight
        ) {
            return
        }

        if (source == HomeMusicSource.Yandex) {
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
            }
        },
        onWaveClick = {
            component.waveRepository.requestWave()
            onOpenPlayer()
        },
        onAllTracksClick = {},
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
                modifier = radialModifier,
            )
        },
        searchContent = { searchModifier ->
            SearchScreen(
                selectedSource = selectedSource,
                onSourceSelected = ::selectMusicSource,
                state = searchState,
                onQueryChange = searchModel::updateQuery,
                loadTrackCover = { item ->
                    withContext(Dispatchers.IO) {
                        when (val source = item.source) {
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
                            searchModel.playTrack(item)
                            onOpenPlayer()
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
                savingYandexTrackIds = localDownloads.keys,
                onRequestLocalTrackDownload = onRequestLocalTrackDownload,
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
