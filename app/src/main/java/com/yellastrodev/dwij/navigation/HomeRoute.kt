package com.yellastrodev.dwij.navigation

import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yellastrodev.dwij.ui.HomeCompactPlayerUiState
import com.yellastrodev.dwij.HomeMusicSource
import com.yellastrodev.dwij.ui.HomeScreen
import com.yellastrodev.dwij.RadialMenu
import com.yellastrodev.dwij.RadialMenuAnimationStyle
import com.yellastrodev.dwij.SearchScreen
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.data.source.requiredAudioPermission
import com.yellastrodev.dwij.data.source.requiredLocalMediaPermissions
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.models.SearchModel
import com.yellastrodev.dwij.models.SearchResultItemUiModel
import com.yellastrodev.dwij.models.SearchTrackSource
import com.yellastrodev.dwij.ui.toImageBitmapOrNull
import com.yellastrodev.dwij.work.LocalLibrarySyncWorker
import com.yellastrodev.dwij.yApplication
import com.yellastrodev.yandexmusiclib.entities.CoverSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Связывает домашний экран с общим плеером,
 * разрешением локальной медиатеки и Compose-навигацией.
 */
@Composable
fun HomeRoute(
    navController: NavHostController,
    playerModel: PlayerModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val application =
        context.applicationContext as yApplication

    val musicSourceSelectionStore =
        application.musicSourceSelectionStore

    val coroutineScope =
        rememberCoroutineScope()

    val selectedSource by
    musicSourceSelectionStore
        .selectedSource
        .collectAsState()

    val searchModel = viewModel<SearchModel>(
        factory = SearchModel.Factory(
            repository =
                application.searchRepository,
            localMusicRepository =
                application.localMusicRepository,
            trackRepository =
                application.trackRepository,
            songRepository =
                application.songRepository,
            playerRepository =
                application.playerRepo,
        ),
    )

    val searchState by
    searchModel.state.collectAsState()

    val track by
    playerModel.track.collectAsState()

    val playbackTrack by
    playerModel.playbackTrack.collectAsState()

    val playerState by
    playerModel.playerState.collectAsState()

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
        R.string.home_player_unknown_artist,
    )

    val audioPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts
                .RequestMultiplePermissions(),
        ) { permissions ->

            permissionRequestInFlight = false

            val granted =
                requiredLocalMediaPermissions()
                    .all { permission ->
                        permissions[permission] == true ||
                                ContextCompat
                                    .checkSelfPermission(
                                        context,
                                        permission,
                                    ) ==
                                PackageManager
                                    .PERMISSION_GRANTED
                    }

            if (granted) {
                musicSourceSelectionStore.select(
                    HomeMusicSource.Local,
                )

                LocalLibrarySyncWorker
                    .enqueueImmediate(
                        context.applicationContext,
                    )
            } else {
                Log.w(
                    TAG,
                    "[audioPermissionLauncher] " +
                            "Доступ к локальной музыке " +
                            "не выдан",
                )

                musicSourceSelectionStore.select(
                    HomeMusicSource.Yandex,
                )
            }
        }

    LaunchedEffect(Unit) {
        val restored =
            musicSourceSelectionStore.restore()

        if (
            restored == HomeMusicSource.Local &&
            ContextCompat.checkSelfPermission(
                context,
                requiredAudioPermission(),
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            musicSourceSelectionStore.select(
                HomeMusicSource.Yandex,
            )
        }
    }

    LaunchedEffect(selectedSource) {
        searchModel.setYandexEnabled(
            selectedSource ==
                    HomeMusicSource.Yandex,
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

    fun selectMusicSource(
        source: HomeMusicSource,
    ) {
        if (source == selectedSource) {
            return
        }

        if (source == HomeMusicSource.Yandex) {
            musicSourceSelectionStore.select(source)
            return
        }

        val permissions =
            requiredLocalMediaPermissions()

        val alreadyGranted =
            permissions.all { permission ->
                ContextCompat.checkSelfPermission(
                    context,
                    permission,
                ) == PackageManager.PERMISSION_GRANTED
            }

        if (alreadyGranted) {
            musicSourceSelectionStore.select(source)

            LocalLibrarySyncWorker
                .enqueueImmediate(
                    context.applicationContext,
                )
        } else if (!permissionRequestInFlight) {
            permissionRequestInFlight = true

            musicSourceSelectionStore.preview(
                HomeMusicSource.Local,
            )

            audioPermissionLauncher.launch(
                permissions,
            )
        }
    }

    HomeScreen(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.safeDrawing,
            ),

        onSettingsClick = {
            navController.navigate(
                DwijDestination.SETTINGS,
            )
        },

        onSongMatchesClick = {
            navController.navigate(
                DwijDestination.SONG_MATCHES,
            )
        },

        onPlaylistsClick = {
            navController.navigate(
                DwijDestination.PLAYLISTS,
            )
        },

        onTracksClick = {
            if (
                selectedSource ==
                HomeMusicSource.Local
            ) {
                navController.navigate(
                    DwijDestination
                        .localLibraryRoute(
                            DwijDestination
                                .LOCAL_MODE_ALL_TRACKS,
                        ),
                )
            } else {
                navController.navigate(
                    DwijDestination.objectRoute(
                        DwijDestination
                            .OBJECT_TYPE_TRACKLIST,
                    ),
                )
            }
        },

        onWaveClick = {
            application
                .waveRepository
                .requestWave()

            navController.navigate(
                DwijDestination.PLAYER,
            )
        },

        onAllTracksClick = {},

        onCatalogClick = {
            navController.navigate(
                DwijDestination.PLAYLISTS,
            )
        },

        onPlayerOpenClick = {
            navController.navigate(
                DwijDestination.PLAYER,
            )
        },

        onPlayerPlayPauseClick =
            playerModel::playAudio,

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
                artist = currentTrack
                    .artistNames
                    .joinToString(", ")
                    .ifBlank {
                        unknownArtist
                    },
                cover = cover,
                isPlaying =
                    playerState.isPlaying,
                currentPositionMillis =
                    playerState.currentPosition,
                durationMillis =
                    playerState.duration,
            )
        },

        selectedSource = selectedSource,

        onSourceSelected =
            ::selectMusicSource,

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
                                application
                                    .coverRepository
                                    .getTrackCover(
                                        track = source.track,
                                        size = CoverSize.`100x100`,
                                    )
                                    ?.toImageBitmapOrNull()
                            }

                            is SearchTrackSource.Local -> {
                                source.song
                                    .localInstances
                                    .firstOrNull()
                                    ?.let { instance ->
                                        playerModel
                                            .cover(
                                                instance = instance,
                                                maxEdgePx = 100,
                                            )
                                            .first()
                                    }
                            }
                        }
                    }
                },
                loadEntityCover = { key, uri ->
                    withContext(Dispatchers.IO) {
                        application
                            .coverRepository
                            .getRemoteCover(
                                entityType = "search",
                                entityId = key,
                                url = uri,
                                size = CoverSize.`100x100`,
                            )
                            ?.toImageBitmapOrNull()
                    }
                },
                onResultClick = { item ->
                    if (item is SearchResultItemUiModel.Track) {
                        searchModel.playTrack(item)
                        navController.navigate(DwijDestination.PLAYER)
                    } else {
                        Log.d(
                            TAG,
                            "[onSearchResultClick] Нажат результат key=${item.key}",
                        )
                    }
                },
                modifier = searchModifier,
            )
        },
    )
}

private const val TAG = "HomeRoute"
