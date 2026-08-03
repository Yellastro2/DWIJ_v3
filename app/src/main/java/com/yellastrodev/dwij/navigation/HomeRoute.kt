package com.yellastrodev.dwij.navigation

import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.yellastrodev.dwij.HomeCompactPlayerUiState
import com.yellastrodev.dwij.HomeMusicSource
import com.yellastrodev.dwij.HomeScreen
import com.yellastrodev.dwij.MusicSourceSelectionStore
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.data.repo.LocalMusicRepository
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.work.LocalLibrarySyncWorker
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Связывает домашний экран с общим плеером, разрешением локальной медиатеки и Compose-навигацией.
 */
@Composable
fun HomeRoute(
    navController: NavHostController,
    playerModel: PlayerModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val application = context.applicationContext as yApplication
    val coroutineScope = rememberCoroutineScope()
    val selectedSource by MusicSourceSelectionStore.selectedSource.collectAsState()
    val track by playerModel.track.collectAsState()
    val playbackTrack by playerModel.playbackTrack.collectAsState()
    val playerState by playerModel.playerState.collectAsState()
    var permissionRequestInFlight by remember { mutableStateOf(false) }
    var cover by remember(track?.id, playbackTrack?.instanceId) {
        mutableStateOf<ImageBitmap?>(null)
    }
    val unknownArtist = stringResource(R.string.home_player_unknown_artist)

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        permissionRequestInFlight = false
        val granted = LocalMusicRepository.requiredPermissions().all { permission ->
            permissions[permission] == true || ContextCompat.checkSelfPermission(
                context,
                permission,
            ) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) {
            MusicSourceSelectionStore.select(context, HomeMusicSource.Local)
            LocalLibrarySyncWorker.enqueueImmediate(context.applicationContext)
        } else {
            Log.w(TAG, "[audioPermissionLauncher] Доступ к локальной музыке не выдан")
            MusicSourceSelectionStore.select(context, HomeMusicSource.Yandex)
        }
    }

    LaunchedEffect(Unit) {
        val restored = MusicSourceSelectionStore.restore(context)
        if (
            restored == HomeMusicSource.Local &&
            ContextCompat.checkSelfPermission(
                context,
                LocalMusicRepository.requiredAudioPermission(),
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            MusicSourceSelectionStore.select(context, HomeMusicSource.Yandex)
        }
    }

    LaunchedEffect(track?.id, playbackTrack?.instanceId) {
        cover = null
        track?.let { currentTrack ->
            playerModel.cover(currentTrack)
                .flowOn(Dispatchers.IO)
                .collect { bitmap -> cover = bitmap.asImageBitmap() }
        }
    }

    fun selectMusicSource(source: HomeMusicSource) {
        if (source == selectedSource) return
        if (source == HomeMusicSource.Yandex) {
            MusicSourceSelectionStore.select(context, source)
            return
        }
        val permissions = LocalMusicRepository.requiredPermissions()
        if (permissions.all { permission ->
                ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
            }
        ) {
            MusicSourceSelectionStore.select(context, source)
            LocalLibrarySyncWorker.enqueueImmediate(context.applicationContext)
        } else if (!permissionRequestInFlight) {
            permissionRequestInFlight = true
            MusicSourceSelectionStore.preview(HomeMusicSource.Local)
            audioPermissionLauncher.launch(permissions)
        }
    }

    HomeScreen(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        onSettingsClick = { navController.navigate(DwijDestination.SETTINGS) },
        onSongMatchesClick = { navController.navigate(DwijDestination.SONG_MATCHES) },
        onPlaylistsClick = { navController.navigate(DwijDestination.PLAYLISTS) },
        onTracksClick = {
            if (selectedSource == HomeMusicSource.Local) {
                navController.navigate(
                    DwijDestination.localLibraryRoute(DwijDestination.LOCAL_MODE_ALL_TRACKS),
                )
            } else {
                navController.navigate(
                    DwijDestination.objectRoute(DwijDestination.OBJECT_TYPE_TRACKLIST),
                )
            }
        },
        onWaveClick = {
            coroutineScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) { application.waveRepository.playWave() }
                }.onSuccess {
                    navController.navigate(DwijDestination.PLAYER)
                }.onFailure { error ->
                    Log.e(TAG, "[playWave] Не удалось запустить волну", error)
                }
            }
        },
        onAllTracksClick = {},
        onCatalogClick = { navController.navigate(DwijDestination.PLAYLISTS) },
        onPlayerOpenClick = { navController.navigate(DwijDestination.PLAYER) },
        onPlayerPlayPauseClick = playerModel::playAudio,
        onPlayerPreviousClick = { coroutineScope.launch { playerModel.prevTrack() } },
        onPlayerNextClick = { coroutineScope.launch { playerModel.nextTrack() } },
        player = track?.let { currentTrack ->
            HomeCompactPlayerUiState(
                title = currentTrack.title,
                artist = currentTrack.artistNames.joinToString(", ").ifBlank { unknownArtist },
                cover = cover,
                isPlaying = playerState.isPlaying,
                currentPositionMillis = playerState.currentPosition,
                durationMillis = playerState.duration,
            )
        },
        selectedSource = selectedSource,
        onSourceSelected = ::selectMusicSource,
    )
}

private const val TAG = "HomeRoute"
