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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yellastrodev.dwij.HomeCompactPlayer
import com.yellastrodev.dwij.HomeCompactPlayerUiState
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.models.PlayerModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * Единый Compose-корень приложения: back stack, маршруты и общий компактный плеер.
 *
 * `NavHost` держит в памяти только back stack и сохранённое состояние маршрутов; невидимые
 * экраны не рисуются и не участвуют в layout.
 */
@Composable
fun DwijApp(
    playerModel: PlayerModel,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val track by playerModel.track.collectAsState()
    val playbackTrack by playerModel.playbackTrack.collectAsState()
    val playerState by playerModel.playerState.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var compactCover by remember(track?.id, playbackTrack?.instanceId) {
        mutableStateOf<ImageBitmap?>(null)
    }
    val unknownArtist = stringResource(R.string.home_player_unknown_artist)
    val showCompactPlayer = track != null &&
        currentRoute != DwijDestination.HOME &&
        currentRoute != DwijDestination.PLAYER

    LaunchedEffect(track?.id, playbackTrack?.instanceId) {
        compactCover = null
        track?.let { currentTrack ->
            playerModel.cover(currentTrack)
                .flowOn(Dispatchers.IO)
                .collect { bitmap -> compactCover = bitmap.asImageBitmap() }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(colorResource(R.color.background)),
        containerColor = colorResource(R.color.background),
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
                                isPlaying = playerState.isPlaying,
                                currentPositionMillis = playerState.currentPosition,
                                durationMillis = playerState.duration,
                            ),
                            onOpenClick = {
                                navController.navigate(DwijDestination.PLAYER)
                            },
                            onPlayPauseClick = playerModel::playAudio,
                            onNextClick = {
                                coroutineScope.launch { playerModel.nextTrack() }
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
                HomeRoute(navController = navController, playerModel = playerModel)
            }
            composable(DwijDestination.PLAYLISTS) {
                PlaylistGridRoute(navController = navController)
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
                    navController = navController,
                    trackToAdd = entry.arguments
                        ?.getString(DwijDestination.ARG_TRACK_TO_ADD),
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
                    navController = navController,
                    playerModel = playerModel,
                    objectType = entry.arguments
                        ?.getString(DwijDestination.ARG_OBJECT_TYPE)
                        .orEmpty(),
                    objectValue = entry.arguments
                        ?.getString(DwijDestination.ARG_OBJECT_VALUE)
                        .orEmpty(),
                )
            }
            composable(DwijDestination.PLAYER) {
                PlayerRoute(navController = navController, playerModel = playerModel)
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
                    navController = navController,
                    mode = entry.arguments
                        ?.getString(DwijDestination.ARG_LOCAL_MODE)
                        .orEmpty(),
                    playlistId = entry.arguments
                        ?.getString(DwijDestination.ARG_LOCAL_PLAYLIST_ID),
                )
            }
            composable(DwijDestination.SONG_MATCHES) {
                SongMatchCandidatesRoute(
                    navController = navController,
                    playerModel = playerModel,
                )
            }
            composable(DwijDestination.SETTINGS) {
                SettingsRoute(navController = navController)
            }
        }
    }
}
