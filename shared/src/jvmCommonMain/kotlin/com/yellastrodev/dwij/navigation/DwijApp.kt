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
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.savedstate.read
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.resources.Res
import com.yellastrodev.dwij.resources.home_player_unknown_artist
import com.yellastrodev.dwij.ui.HomeCompactPlayer
import com.yellastrodev.dwij.ui.HomeCompactPlayerUiState
import com.yellastrodev.dwij.ui.theme.DwijColors
import com.yellastrodev.dwij.utils.TrackChangeDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Единый multiplatform Compose-корень приложения.
 *
 * Владеет NavController, back stack, графом маршрутов и общим компактным плеером.
 * Платформа предоставляет только системные возможности отдельных экранов.
 */
@Composable
fun DwijApp(
    playerModel: PlayerModel,
    component: DwijComponent,
    platform: DwijAppPlatform,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val track by playerModel.track.collectAsState()
    val playbackTrack by playerModel.playbackTrack.collectAsState()
    val playerState by playerModel.playerState.collectAsState()

    val coroutineScope = rememberCoroutineScope()

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
                navController.navigateUp()
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

    Scaffold(
        modifier = modifier
            .then(globalInputModifier)
            .fillMaxSize()
            .background(DwijColors.Background),
        containerColor = DwijColors.Background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showCompactPlayer) {
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
                    onOpenPlayer = {
                        navController.navigate(
                            DwijDestination.PLAYER,
                        )
                    },
                )
            }

            composable(DwijDestination.PLAYLISTS) {
                PlaylistGridRoute(
                    component = component,
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
                    onBackClick = {
                        navController.navigateUp()
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
        }
    }
}

/** Читает строковый аргумент маршрута через multiplatform SavedState API. */
private fun NavBackStackEntry.stringArgument(
    key: String,
): String? =
    arguments?.read {
        getStringOrNull(key)
    }
