package com.yellastrodev.dwij.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.unit.dp
import com.yellastrodev.dwij.ui.LocalRadialMenuSelection
import com.yellastrodev.dwij.ui.RadialMenuSelection
import com.yellastrodev.dwij.ui.homeRadialMenuItems
import com.yellastrodev.dwij.RADIAL_MENU_PRIMARY_SELECTION
import com.yellastrodev.dwij.RADIAL_MENU_ADD_SELECTION
import com.yellastrodev.dwij.resources.radial_menu_new_sector
import com.yellastrodev.dwij.resources.radial_menu_primary_title
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.read
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.dSimpleTracklist
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.resources.Res
import com.yellastrodev.dwij.resources.home_player_unknown_artist
import com.yellastrodev.dwij.resources.auth_required_cancel
import com.yellastrodev.dwij.resources.auth_required_confirm
import com.yellastrodev.dwij.resources.auth_required_message
import com.yellastrodev.dwij.resources.auth_required_title
import com.yellastrodev.dwij.resources.radial_menu_selection_prompt
import com.yellastrodev.dwij.resources.playlists_cancel
import com.yellastrodev.dwij.ui.HomeCompactPlayer
import com.yellastrodev.dwij.ui.HomeCompactPlayerUiState
import com.yellastrodev.dwij.ui.RadialMenuSettingsScreen
import com.yellastrodev.dwij.ui.theme.DwijColors
import com.yellastrodev.dwij.utils.TrackChangeDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Единый multiplatform Compose-корень приложения.
 *
 * Владеет NavController, back stack, графом маршрутов, компактным плеером и общими ошибками воспроизведения.
 * Платформа предоставляет только системные возможности отдельных экранов.
 * Входящие ссылки на треки превращает в очередь воспроизведения и открывает плеер.
 * Входящие ссылки на альбомы открывает в существующем экране каталога.
 * Меню VK-треков/плейлистов передают загрузки общей платформенной очереди.
 * Общая фонотека VK использует маршрут виртуального плейлиста и тот же экран очереди.
 * Конечные рекомендации VK используют отдельную сетку и общий экран VK-треклиста.
 * Навигационный каталог VK открывает готовые коллекции, включая личные треки.
 * Радиальное меню имеет отдельный экран настройки с раскрытым предпросмотром.
 * Временный режим назначения сохраняет выбор или добавляет сектор без воспроизведения и возвращает к настройкам.
 * Центральная кнопка выбирает назначение тем же путём, отдельно от списка секторов.
 */
@Composable
fun DwijApp(
    playerModel: PlayerModel,
    component: DwijComponent,
    platform: DwijAppPlatform,
    modifier: Modifier = Modifier,
    playerOpenRequests: Flow<Unit> = emptyFlow(),
    yandexTrackRequests: Flow<String> = emptyFlow(),
    yandexAlbumRequests: Flow<Int> = emptyFlow(),
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    var selectingRadialSlot by rememberSaveable { mutableStateOf<Int?>(null) }
    val radialTargets by component.radialMenuSettingsStore.targets.collectAsState()
    val radialItems = homeRadialMenuItems(radialTargets)

    /** Завершает выбор без изменения назначения и удаляет временные экраны из back stack. */
    fun cancelRadialSelection() {
        selectingRadialSlot = null
        if (!navController.popBackStack(DwijDestination.RADIAL_MENU_SETTINGS, false)) {
            navController.navigate(DwijDestination.RADIAL_MENU_SETTINGS) { launchSingleTop = true }
        }
    }
    val radialSelection = selectingRadialSlot?.let { index ->
        RadialMenuSelection { target ->
            if (selectingRadialSlot == index) {
                when (index) {
                    RADIAL_MENU_PRIMARY_SELECTION -> component.radialMenuSettingsStore.setPrimaryTarget(target)
                    RADIAL_MENU_ADD_SELECTION -> component.radialMenuSettingsStore.addTarget(target)
                    else -> component.radialMenuSettingsStore.setTarget(index, target)
                }
                cancelRadialSelection()
            }
        }
    }
    LaunchedEffect(currentRoute) {
        if (currentRoute == DwijDestination.RADIAL_MENU_SETTINGS) selectingRadialSlot = null
    }

    val track by playerModel.track.collectAsState()
    val playbackTrack by playerModel.playbackTrack.collectAsState()
    val playerState by playerModel.playerState.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val localTrackDownloadRequester =
        platform.rememberLocalTrackDownloadRequester()
    val shareRequester = platform.rememberShareRequester()

    LaunchedEffect(navController, playerOpenRequests) {
        playerOpenRequests.collect {
            navController.navigate(DwijDestination.PLAYER) {
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(navController, yandexAlbumRequests, component) {
        yandexAlbumRequests.collect { albumId ->
            component.logger.info("DwijApp", "[openYandexAlbum] Открываем альбом из ссылки: $albumId")
            navController.navigate(
                DwijDestination.objectRoute(
                    type = DwijDestination.OBJECT_TYPE_ALBUM,
                    value = albumId.toString(),
                ),
            ) {
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(navController, yandexTrackRequests, component) {
        yandexTrackRequests.collect { trackId ->
            when (val result = component.trackRepository.getTrack(trackId)) {
                is DataResult.Success -> {
                    component.trackRepository.putTracks(listOf(result.value))
                    val songs = component.songRepository.songsForYandexTracks(listOf(result.value))
                    if (songs.isNotEmpty()) {
                        component.playerRepo.playQueue(
                            songs = songs,
                            startIndex = 0,
                            tracklist = dSimpleTracklist(),
                        )
                        navController.navigate(DwijDestination.PLAYER) {
                            launchSingleTop = true
                        }
                    }
                }
                is DataResult.Failure -> Unit
            }
        }
    }

    var showAuthorizationRequiredDialog by remember(component) {
        mutableStateOf(false)
    }

    LaunchedEffect(component) {
        component
            .yandexAuthorizationRequiredNotifier
            .events
            .collect {
                showAuthorizationRequiredDialog = true
            }
    }

    val globalInputModifier =
        platform.globalInputModifier(
            hasActiveTrack =
                track != null,
            onPlayPause =
                playerModel::playAudio,
            onPrevious = {
                coroutineScope.launch {
                    playerModel.prevTrack()
                }
            },
            onNext = {
                coroutineScope.launch {
                    playerModel.nextTrack()
                }
            },
            onBack = {
                if (selectingRadialSlot != null && currentRoute == DwijDestination.HOME) cancelRadialSelection()
                else navController.navigateUp()
            },
        )

    var compactCover by remember(
        track?.id,
        playbackTrack?.instanceId,
    ) {
        mutableStateOf<ImageBitmap?>(null)
    }

    val unknownArtist = stringResource(
        Res.string.home_player_unknown_artist,
    )

    val showCompactPlayer =
        track != null &&
            currentRoute != DwijDestination.HOME &&
            currentRoute != DwijDestination.PLAYER

    LaunchedEffect(
        track?.id,
        playbackTrack?.instanceId,
    ) {
        compactCover = null

        track?.let { currentTrack ->
            playerModel
                .cover(currentTrack)
                .flowOn(Dispatchers.IO)
                .collect { imageBitmap ->
                    compactCover = imageBitmap
                }
        }
    }

    val playbackSnackbar = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(playerModel) {
        playerModel.playerEvent.collect { event ->
            if (event is com.yellastrodev.dwij.utils.PlayerEvent.ShowError) {
                playbackSnackbar.currentSnackbarData?.dismiss()
                launch { playbackSnackbar.showSnackbar(event.message) }
            }
        }
    }
    CompositionLocalProvider(LocalRadialMenuSelection provides radialSelection) {
    Scaffold(
        modifier = modifier
            .then(globalInputModifier)
            .fillMaxSize()
            .background(DwijColors.Background),
        containerColor = DwijColors.Background,
        snackbarHost = { androidx.compose.material3.SnackbarHost(playbackSnackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            selectingRadialSlot?.let { index ->
                Row(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
                    Text(
                        stringResource(
                            Res.string.radial_menu_selection_prompt,
                            when (index) {
                                RADIAL_MENU_PRIMARY_SELECTION -> stringResource(Res.string.radial_menu_primary_title)
                                RADIAL_MENU_ADD_SELECTION -> stringResource(Res.string.radial_menu_new_sector)
                                else -> radialItems[index].title.replace('\n', ' ')
                            },
                        ),
                        color = DwijColors.White,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = ::cancelRadialSelection) { Text(stringResource(Res.string.playlists_cancel)) }
                }
            }
        },
        bottomBar = {
            if (showCompactPlayer && selectingRadialSlot == null) {
                track?.let { currentTrack ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding(),
                    ) {
                        HomeCompactPlayer(
                            player = HomeCompactPlayerUiState(
                                title = currentTrack.title,
                                artist = currentTrack.artistNames
                                    .joinToString(", ")
                                    .ifBlank { unknownArtist },
                                cover = compactCover,
                                isPlaying = playerState.wantsToPlay,
                                currentPositionMillis =
                                    playerState.currentPosition,
                                durationMillis =
                                    playerState.duration,
                                isNextPending =
                                    playerState.pendingTrackChange ==
                                        TrackChangeDirection.NEXT,
                            ),
                            onOpenClick = {
                                navController.navigate(
                                    DwijDestination.PLAYER,
                                )
                            },
                            onPlayPauseClick =
                                playerModel::playAudio,
                            onNextClick = {
                                coroutineScope.launch {
                                    playerModel.nextTrack()
                                }
                            },
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        NavHost(
            navController = navController,
            startDestination = DwijDestination.HOME,
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .consumeWindowInsets(contentPadding),
        ) {
            composable(DwijDestination.HOME) {
                HomeRoute(
                    component = component,
                    playerModel = playerModel,
                    routePlatform =
                        platform.rememberHomeRoutePlatform(),
                    screenPlatform =
                        platform.homeScreenPlatform,
                    onOpenSettings = {
                        navController.navigate(
                            DwijDestination.SETTINGS,
                        )
                    },
                    onOpenRadialMenuSettings = {
                        navController.navigate(DwijDestination.RADIAL_MENU_SETTINGS)
                    },
                    onOpenSongMatches = {
                        navController.navigate(
                            DwijDestination.SONG_MATCHES,
                        )
                    },
                    onOpenPlaylists = {
                        navController.navigate(
                            DwijDestination.PLAYLISTS,
                        )
                    },
                    onOpenWaves = {
                        navController.navigate(
                            DwijDestination.WAVES,
                        )
                    },
                    onOpenVkRecommendations = {
                        navController.navigate(DwijDestination.VK_RECOMMENDATIONS)
                    },
                    onOpenYandexPlaylist = { playlistId ->
                        navController.navigate(
                            DwijDestination.objectRoute(
                                type = DwijDestination.OBJECT_TYPE_PLAYLIST,
                                value = playlistId,
                            ),
                        )
                    },
                    onOpenArtists = {
                        navController.navigate(
                            DwijDestination.CATALOG_ARTISTS,
                        )
                    },
                    onOpenAlbums = {
                        navController.navigate(
                            DwijDestination.CATALOG_ALBUMS,
                        )
                    },
                    onOpenLocalTracks = {
                        navController.navigate(
                            DwijDestination.localLibraryRoute(
                                DwijDestination.LOCAL_MODE_ALL_TRACKS,
                            ),
                        )
                    },
                    onOpenYandexTracks = {
                        navController.navigate(
                            DwijDestination.objectRoute(
                                DwijDestination.OBJECT_TYPE_TRACKLIST,
                            ),
                        )
                    },
                    onOpenVkTracks = {
                        navController.navigate(DwijDestination.objectRoute(
                            type = DwijDestination.OBJECT_TYPE_VK_PLAYLIST,
                            value = com.yellastrodev.dwij.data.repo.VK_ALL_TRACKS,
                        ))
                    },
                    onOpenVkMyTracks = {
                        navController.navigate(DwijDestination.objectRoute(
                            type = DwijDestination.OBJECT_TYPE_VK_PLAYLIST,
                            value = com.yellastrodev.dwij.data.repo.VK_MY_TRACKS,
                        ))
                    },
                    onOpenCatalogObject = { type, externalId ->
                        navController.navigate(
                            DwijDestination.objectRoute(
                                type = type,
                                value = externalId.toString(),
                            ),
                        )
                    },
                    onOpenPlayer = {
                        navController.navigate(
                            DwijDestination.PLAYER,
                        )
                    },
                    onRequestLocalTrackDownload =
                        localTrackDownloadRequester::request,
                    onShareYandexUrl = shareRequester::share,
                )
            }

            composable(DwijDestination.RADIAL_MENU_SETTINGS) {
                val targets by component.radialMenuSettingsStore.targets.collectAsState()
                val primaryTarget by component.radialMenuSettingsStore.primaryTarget.collectAsState()
                RadialMenuSettingsScreen(
                    targets = targets,
                    primaryTarget = primaryTarget,
                    onRemoveSlot = component.radialMenuSettingsStore::removeTarget,
                    onAddSlot = {
                        selectingRadialSlot = RADIAL_MENU_ADD_SELECTION
                        navController.navigate(DwijDestination.HOME)
                    },
                    onSelectPrimary = {
                        selectingRadialSlot = RADIAL_MENU_PRIMARY_SELECTION
                        navController.navigate(DwijDestination.HOME)
                    },
                    onSelectSlot = { index ->
                        selectingRadialSlot = index
                        navController.navigate(DwijDestination.HOME)
                    },
                    onReset = component.radialMenuSettingsStore::reset,
                    onBackClick = { navController.navigateUp() },
                )
            }

            composable(DwijDestination.PLAYLISTS) {
                PlaylistGridRoute(
                    component = component,
                    onRequestLocalTrackDownloads = localTrackDownloadRequester::requestAll,
                    platform =
                        platform.rememberPlaylistGridPlatform(),
                    onOpenYandexPlaylist = { playlistId ->
                        navController.navigate(
                            DwijDestination.objectRoute(
                                type = DwijDestination.OBJECT_TYPE_PLAYLIST,
                                value = playlistId,
                            ),
                        )
                    },
                    onOpenLocalPlaylist = { playlistId ->
                        navController.navigate(
                            DwijDestination.localLibraryRoute(
                                mode = DwijDestination.LOCAL_MODE_PLAYLIST,
                                playlistId = playlistId,
                            ),
                        )
                    },
                    onOpenVkPlaylist = { playlistId ->
                        navController.navigate(DwijDestination.objectRoute(DwijDestination.OBJECT_TYPE_VK_PLAYLIST, playlistId))
                    },
                    onBackClick = {
                        navController.navigateUp()
                    },
                )
            }

            composable(DwijDestination.VK_RECOMMENDATIONS) {
                VkRecommendationsRoute(component,
                    onBackClick = { navController.navigateUp() },
                    onOpenTracks = { id -> navController.navigate(
                        DwijDestination.objectRoute(DwijDestination.OBJECT_TYPE_VK_PLAYLIST, id)) },
                )
            }
            composable(DwijDestination.WAVES) {
                WaveGridRoute(
                    component = component,
                    onBackClick = {
                        navController.navigateUp()
                    },
                    onOpenPlayer = {
                        navController.navigate(
                            DwijDestination.PLAYER,
                        )
                    },
                )
            }

            composable(DwijDestination.CATALOG_ARTISTS) {
                CatalogEntityListRoute(
                    component = component,
                    kind = CatalogEntityListKind.Artists,
                    onBackClick = {
                        navController.navigateUp()
                    },
                    onOpenCatalogObject = { type, externalId ->
                        navController.navigate(
                            DwijDestination.objectRoute(
                                type = type,
                                value = externalId.toString(),
                            ),
                        )
                    },
                )
            }

            composable(DwijDestination.CATALOG_ALBUMS) {
                CatalogEntityListRoute(
                    component = component,
                    kind = CatalogEntityListKind.Albums,
                    onBackClick = {
                        navController.navigateUp()
                    },
                    onOpenCatalogObject = { type, externalId ->
                        navController.navigate(
                            DwijDestination.objectRoute(
                                type = type,
                                value = externalId.toString(),
                            ),
                        )
                    },
                )
            }

            composable(
                route = DwijDestination.PLAYLISTS_ADD_PATTERN,
                arguments = listOf(
                    navArgument(DwijDestination.ARG_TRACK_TO_ADD) {
                        type = NavType.StringType
                    },
                ),
            ) { entry ->
                PlaylistGridRoute(
                    component = component,
                    onRequestLocalTrackDownloads = localTrackDownloadRequester::requestAll,
                    platform =
                        platform.rememberPlaylistGridPlatform(),
                    trackToAdd = entry.stringArgument(
                        DwijDestination.ARG_TRACK_TO_ADD,
                    ),
                    onOpenYandexPlaylist = { playlistId ->
                        navController.navigate(
                            DwijDestination.objectRoute(
                                type = DwijDestination.OBJECT_TYPE_PLAYLIST,
                                value = playlistId,
                            ),
                        )
                    },
                    onOpenLocalPlaylist = { playlistId ->
                        navController.navigate(
                            DwijDestination.localLibraryRoute(
                                mode = DwijDestination.LOCAL_MODE_PLAYLIST,
                                playlistId = playlistId,
                            ),
                        )
                    },
                    onOpenVkPlaylist = { playlistId ->
                        navController.navigate(DwijDestination.objectRoute(DwijDestination.OBJECT_TYPE_VK_PLAYLIST, playlistId))
                    },
                    onBackClick = {
                        navController.navigateUp()
                    },
                )
            }

            composable(
                route = DwijDestination.OBJECT_PATTERN,
                arguments = listOf(
                    navArgument(DwijDestination.ARG_OBJECT_TYPE) {
                        type = NavType.StringType
                    },
                    navArgument(DwijDestination.ARG_OBJECT_VALUE) {
                        type = NavType.StringType
                    },
                ),
            ) { entry ->
                ObjectRoute(
                    component = component,
                    playerModel = playerModel,
                    objectType = entry.stringArgument(
                        DwijDestination.ARG_OBJECT_TYPE,
                    ).orEmpty(),
                    objectValue = entry.stringArgument(
                        DwijDestination.ARG_OBJECT_VALUE,
                    ).orEmpty(),
                    onBackClick = {
                        navController.navigateUp()
                    },
                    onOpenPlayer = {
                        navController.navigate(
                            DwijDestination.PLAYER,
                        )
                    },
                    onRequestLocalTrackDownload =
                        localTrackDownloadRequester::request,
                    onRequestLocalTrackDownloads =
                        localTrackDownloadRequester::requestAll,
                    onShareYandexUrl = shareRequester::share,
                    onAddToPlaylist = { trackId ->
                        navController.navigate(DwijDestination.playlistsAddRoute(trackId))
                    },
                )
            }

            composable(DwijDestination.PLAYER) {
                PlayerRoute(
                    component = component,
                    playerModel = playerModel,
                    onBackClick = {
                        navController.navigateUp()
                    },
                    onAddToPlaylist = { trackId ->
                        navController.navigate(
                            DwijDestination.playlistsAddRoute(trackId),
                        )
                    },
                    onOpenArtist = { artistId ->
                        navController.navigate(
                            DwijDestination.objectRoute(
                                type = DwijDestination.OBJECT_TYPE_ARTIST,
                                value = artistId.toString(),
                            ),
                        )
                    },
                    onRequestLocalTrackDownload =
                        localTrackDownloadRequester::request,
                    onShareYandexUrl = shareRequester::share,
                )
            }

            composable(
                route = DwijDestination.LOCAL_LIBRARY_PATTERN,
                arguments = listOf(
                    navArgument(DwijDestination.ARG_LOCAL_MODE) {
                        type = NavType.StringType
                    },
                    navArgument(DwijDestination.ARG_LOCAL_PLAYLIST_ID) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                LocalLibraryRoute(
                    component = component,
                    playerModel = playerModel,
                    platform =
                        platform.rememberLocalLibraryPlatform(),
                    mode = entry.stringArgument(
                        DwijDestination.ARG_LOCAL_MODE,
                    ).orEmpty(),
                    playlistId = entry.stringArgument(
                        DwijDestination.ARG_LOCAL_PLAYLIST_ID,
                    ),
                    onOpenPlayer = {
                        navController.navigate(
                            DwijDestination.PLAYER,
                        )
                    },
                    onOpenPlaylist = { playlistId ->
                        navController.navigate(
                            DwijDestination.localLibraryRoute(
                                mode = DwijDestination.LOCAL_MODE_PLAYLIST,
                                playlistId = playlistId,
                            ),
                        )
                    },
                    onBackClick = {
                        navController.navigateUp()
                    },
                )
            }

            composable(DwijDestination.SONG_MATCHES) {
                SongMatchCandidatesRoute(
                    component = component,
                    playerModel = playerModel,
                    onBackClick = {
                        navController.navigateUp()
                    },
                )
            }

            composable(DwijDestination.SETTINGS) {
                SettingsRoute(
                    component = component,
                    platform =
                        platform.rememberSettingsPlatform(),
                    onBackClick = {
                        navController.navigateUp()
                    },
                )
            }

            composable(DwijDestination.SETTINGS_AUTH) {
                SettingsRoute(
                    component = component,
                    platform =
                        platform.rememberSettingsPlatform(),
                    onBackClick = {
                        navController.navigateUp()
                    },
                    startAuthorization = true,
                )
            }
        }
    }

    }
    // Регистрируется после NavHost, чтобы Back с корня выбора возвращал к настройке.
    platform.homeScreenPlatform.BackHandler(
        enabled = selectingRadialSlot != null && currentRoute == DwijDestination.HOME,
        onBack = ::cancelRadialSelection,
    )

    if (showAuthorizationRequiredDialog) {
        AlertDialog(
            onDismissRequest = {
                showAuthorizationRequiredDialog = false
            },
            title = {
                Text(
                    stringResource(
                        Res.string.auth_required_title,
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        Res.string.auth_required_message,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAuthorizationRequiredDialog = false
                        navController.navigate(
                            DwijDestination.SETTINGS_AUTH,
                        )
                    },
                ) {
                    Text(
                        stringResource(
                            Res.string.auth_required_confirm,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAuthorizationRequiredDialog = false
                    },
                ) {
                    Text(
                        stringResource(
                            Res.string.auth_required_cancel,
                        ),
                    )
                }
            },
        )
    }
}

/** Читает строковый аргумент маршрута через multiplatform SavedState API. */
private fun NavBackStackEntry.stringArgument(
    key: String,
): String? =
    arguments?.read {
        getStringOrNull(key)
    }
