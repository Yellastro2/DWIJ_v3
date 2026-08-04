package com.yellastrodev.dwij.navigation

import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yellastrodev.dwij.CreatePlaylistDialog
import com.yellastrodev.dwij.HomeMusicSource
import com.yellastrodev.dwij.MusicSourceSelectionStore
import com.yellastrodev.dwij.PlaylistGridScreen
import com.yellastrodev.dwij.PlaylistGridScreenItem
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntity
import com.yellastrodev.dwij.data.entities.LocalPlaylistOrigin
import com.yellastrodev.dwij.data.entities.LocalPlaylistSummary
import com.yellastrodev.dwij.data.entities.dYaLikeTracklist.Companion.KIND_LIKED
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.repo.LocalMusicRepository
import com.yellastrodev.dwij.models.GridPlaylistModel
import com.yellastrodev.dwij.utils.DurationFormat.Companion.formatDuration
import com.yellastrodev.dwij.utils.LangFormats.Companion.getNumericPostfix
import com.yellastrodev.dwij.work.LocalLibrarySyncWorker
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compose-route общей сетки Яндекс- и локальных плейлистов.
 *
 * [trackToAdd] включает прежний режим выбора плейлиста для добавления трека.
 */
@Composable
fun PlaylistGridRoute(
    navController: NavHostController,
    trackToAdd: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val application = context.applicationContext as yApplication
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val model = viewModel<GridPlaylistModel>(
        factory = GridPlaylistModel.Factory(
            repo = application.playlistRepository,
            trackRepo = application.trackRepository,
            coverRepo = application.coverRepository,
        ),
    )
    val yandexPlaylists by model.playlists.collectAsState()
    val yandexInitialLoadComplete by model.initialLoadComplete.collectAsState()
    val localPlaylistSummarySnapshot by application.localMusicRepository.playlistSummaries
        .collectAsState(initial = null)
    val isLocalSynchronizing by application.localMusicRepository.isSynchronizing.collectAsState()
    val musicSource by MusicSourceSelectionStore.selectedSource.collectAsState()
    val localPlaylistSummaries = localPlaylistSummarySnapshot.orEmpty()
    val localPlaylists = remember(localPlaylistSummaries) {
        localPlaylistSummaries.map(LocalPlaylistSummary::playlist)
    }
    val isAddTrackMode = trackToAdd != null
    // Добавление трека в Яндекс-плейлист не имеет локального аналога. Не меняем глобальный
    // источник пользователя, но для picker-а всегда показываем только Яндекс-плейлисты.
    val screenSource = if (isAddTrackMode) HomeMusicSource.Yandex else musicSource
    var permissionRequestInFlight by remember { mutableStateOf(false) }
    var pickedTrack by remember(trackToAdd) { mutableStateOf<dYaTrack?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var createDialogSource by remember { mutableStateOf<HomeMusicSource?>(null) }
    var isCreatingPlaylist by remember { mutableStateOf(false) }
    var removeTrackRequest by remember { mutableStateOf<Pair<dYaPlaylist, dYaTrack>?>(null) }
    var deleteInfoPlaylist by remember { mutableStateOf<dYaPlaylist?>(null) }

    fun showSnackbar(messageRes: Int) {
        coroutineScope.launch { snackbarHostState.showSnackbar(context.getString(messageRes)) }
    }

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

    fun selectMusicSource(source: HomeMusicSource) {
        if (source == musicSource) return
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
    LaunchedEffect(trackToAdd) {
        if (trackToAdd != null) {
            when (val result = model.getTrack(trackToAdd)) {
                is DataResult.Success -> pickedTrack = result.value
                is DataResult.Failure -> showSnackbar(R.string.playlists_track_load_failed)
            }
        }
    }

    val createTitle = stringResource(R.string.playlists_create)
    val likedTitle = stringResource(R.string.playlists_liked)
    val localDwij = stringResource(R.string.local_playlist_dwij)
    val localMediaStore = stringResource(R.string.local_playlist_media_store)
    val localM3u = stringResource(R.string.local_playlist_m3u)
    val yandexScreenItems = remember(
        yandexPlaylists,
        trackToAdd,
        createTitle,
        likedTitle,
    ) {
        val startedNanos = SystemClock.elapsedRealtimeNanos()
        buildYandexItems(
            playlists = yandexPlaylists,
            pickedTrackId = trackToAdd,
            showCreateAction = !isAddTrackMode,
            createTitle = createTitle,
            likedTitle = likedTitle,
        ).also { items ->
            Log.d(
                TAG,
                "[composeYandexItems] Собрано=${items.size}, " +
                    "время=${elapsedMillis(startedNanos)} мс",
            )
        }
    }
    val localScreenItems = remember(
        localPlaylistSummaries,
        createTitle,
        localDwij,
        localMediaStore,
        localM3u,
    ) {
        val startedNanos = SystemClock.elapsedRealtimeNanos()
        buildLocalItems(
            playlists = localPlaylistSummaries,
            showCreateAction = !isAddTrackMode,
            createTitle = createTitle,
            dwijLabel = localDwij,
            mediaStoreLabel = localMediaStore,
            m3uLabel = localM3u,
        ).also { items ->
            Log.d(
                TAG,
                "[composeLocalItems] Собрано=${items.size}, " +
                    "время=${elapsedMillis(startedNanos)} мс",
            )
        }
    }
    val screenItems = when (screenSource) {
        HomeMusicSource.Yandex -> yandexScreenItems
        HomeMusicSource.Local -> localScreenItems
    }

    fun openYandexPlaylist(playlist: dYaPlaylist) {
        navController.navigate(
            DwijDestination.objectRoute(
                type = DwijDestination.OBJECT_TYPE_PLAYLIST,
                value = playlist.getdId(),
            ),
        )
    }

    fun openLocalPlaylist(playlist: LocalPlaylistEntity) {
        navController.navigate(
            DwijDestination.localLibraryRoute(
                mode = DwijDestination.LOCAL_MODE_PLAYLIST,
                playlistId = playlist.playlistId,
            ),
        )
    }

    fun createPlaylist(source: HomeMusicSource, title: String, isPublic: Boolean) {
        coroutineScope.launch {
            try {
                when (source) {
                    HomeMusicSource.Yandex -> when (
                        val result = model.createPlaylist(title, isPublic)
                    ) {
                        is DataResult.Success -> {
                            isCreatingPlaylist = false
                            createDialogSource = null
                            openYandexPlaylist(result.value)
                        }
                        is DataResult.Failure -> {
                            Log.w(TAG, "[createPlaylist] Яндекс не создал плейлист: ${result.error}")
                            isCreatingPlaylist = false
                            showSnackbar(R.string.playlists_create_failed)
                        }
                    }
                    HomeMusicSource.Local -> when (
                        val result = withContext(Dispatchers.IO) {
                            application.localMusicRepository.saveDwijPlaylist(
                                name = title,
                                trackIds = emptyList(),
                            )
                        }
                    ) {
                        is DataResult.Success -> {
                            isCreatingPlaylist = false
                            createDialogSource = null
                            openLocalPlaylist(result.value)
                        }
                        is DataResult.Failure -> {
                            Log.w(TAG, "[createPlaylist] Локальный плейлист не создан: ${result.error}")
                            isCreatingPlaylist = false
                            showSnackbar(R.string.playlists_create_failed)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "[createPlaylist] Непредвиденная ошибка создания", error)
                isCreatingPlaylist = false
                showSnackbar(R.string.playlists_create_failed)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        PlaylistGridScreen(
            title = stringResource(
                if (isAddTrackMode) {
                    R.string.playlists_add_track_title
                } else {
                    R.string.playlists_title
                },
            ),
            items = screenItems,
            selectedSource = screenSource,
            onSourceSelected = ::selectMusicSource,
            showSourceSelector = !isAddTrackMode,
            onBackClick = { navController.popBackStack() },
            onItemClick = { item ->
                if (item.isCreateAction) {
                    createDialogSource = screenSource
                } else if (screenSource == HomeMusicSource.Local) {
                    if (isAddTrackMode) {
                        showSnackbar(R.string.playlists_local_add_unavailable)
                    } else {
                        localPlaylists.firstOrNull { it.playlistId == item.id }
                            ?.let(::openLocalPlaylist)
                    }
                } else {
                    val playlist = yandexPlaylists.firstOrNull {
                        it.getdId() == item.id
                    }
                    if (playlist != null && trackToAdd == null) {
                        openYandexPlaylist(playlist)
                    } else if (playlist != null && item.highlighted) {
                        val track = pickedTrack
                        if (track == null) {
                            showSnackbar(R.string.playlists_track_load_failed)
                        } else {
                            removeTrackRequest = playlist to track
                        }
                    } else if (playlist != null && trackToAdd != null) {
                        coroutineScope.launch {
                            when (model.addTrackToPlaylist(playlist, trackToAdd)) {
                                is DataResult.Success -> navController.popBackStack()
                                is DataResult.Failure ->
                                    showSnackbar(R.string.playlists_track_add_failed)
                            }
                        }
                    }
                }
            },
            onItemLongClick = { item ->
                if (screenSource == HomeMusicSource.Yandex && !isAddTrackMode) {
                    deleteInfoPlaylist = yandexPlaylists.firstOrNull {
                        it.getdId() == item.id
                    }
                }
            },
            loadCover = { playlistId ->
                val playlist = yandexPlaylists.firstOrNull { it.getdId() == playlistId }
                if (playlist == null || playlist.ogImageUri.isNullOrBlank()) {
                    null
                } else {
                    try {
                        withContext(Dispatchers.IO) {
                            model.getCover(playlist).asImageBitmap()
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.w(
                            TAG,
                            "[loadPlaylistCover] Не загрузилась обложка playlistId=$playlistId",
                            error,
                        )
                        null
                    }
                }
            },
            emptyMessage = stringResource(
                if (screenSource == HomeMusicSource.Local) {
                    R.string.local_playlists_empty
                } else {
                    R.string.playlists_empty_yandex
                },
            ),
            isLoading = when (screenSource) {
                HomeMusicSource.Yandex -> !yandexInitialLoadComplete
                HomeMusicSource.Local -> localPlaylistSummarySnapshot == null ||
                    (localPlaylistSummarySnapshot.isNullOrEmpty() && isLocalSynchronizing)
            },
            isRefreshing = isRefreshing ||
                (screenSource == HomeMusicSource.Local && isLocalSynchronizing),
            onRefresh = {
                if (!isRefreshing) {
                    if (screenSource == HomeMusicSource.Local) {
                        LocalLibrarySyncWorker.enqueueImmediate(context.applicationContext)
                    } else {
                        isRefreshing = true
                        coroutineScope.launch {
                            try {
                                if (model.refreshPlaylists() is DataResult.Failure) {
                                    showSnackbar(R.string.playlists_refresh_failed)
                                }
                            } finally {
                                isRefreshing = false
                            }
                        }
                    }
                }
            },
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
        )
    }

    createDialogSource?.let { source ->
        CreatePlaylistDialog(
            source = source,
            isCreating = isCreatingPlaylist,
            onDismiss = { if (!isCreatingPlaylist) createDialogSource = null },
            onCreate = { title, isPublic ->
                if (!isCreatingPlaylist) {
                    isCreatingPlaylist = true
                    createPlaylist(source, title, isPublic)
                }
            },
        )
    }
    removeTrackRequest?.let { (playlist, track) ->
        AlertDialog(
            onDismissRequest = { removeTrackRequest = null },
            title = { Text(stringResource(R.string.playlists_remove_track_title)) },
            text = { Text(stringResource(R.string.playlists_remove_track_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        removeTrackRequest = null
                        coroutineScope.launch {
                            if (
                                model.removeTrackFromPlaylist(playlist, track) is DataResult.Failure
                            ) {
                                showSnackbar(R.string.playlists_track_remove_failed)
                            }
                        }
                    },
                ) { Text(stringResource(R.string.playlists_remove_track_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { removeTrackRequest = null }) {
                    Text(stringResource(R.string.playlists_cancel))
                }
            },
        )
    }
    deleteInfoPlaylist?.let { playlist ->
        AlertDialog(
            onDismissRequest = { deleteInfoPlaylist = null },
            title = { Text(stringResource(R.string.playlists_delete_title)) },
            text = {
                Text("${playlist.title}\n\n${stringResource(R.string.playlists_delete_message)}")
            },
            confirmButton = {
                TextButton(onClick = { deleteInfoPlaylist = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

/** Собирает UI-модели Яндекс-плейлистов, включая системную плитку создания. */
private fun buildYandexItems(
    playlists: List<dYaPlaylist>,
    pickedTrackId: String?,
    showCreateAction: Boolean,
    createTitle: String,
    likedTitle: String,
): List<PlaylistGridScreenItem> = buildList {
    if (showCreateAction) {
        add(
            PlaylistGridScreenItem(
                id = CREATE_ITEM_ID,
                title = createTitle,
                artworkResId = R.drawable.ic_playlist_create,
                isCreateAction = true,
            ),
        )
    }
    playlists
        .filterNot { pickedTrackId != null && it.kind == KIND_LIKED }
        .forEach { playlist ->
            val trackCount = playlist.trackCount
            val isLikedPlaylist = playlist.kind == KIND_LIKED
            add(
                PlaylistGridScreenItem(
                    id = playlist.getdId(),
                    title = if (isLikedPlaylist) likedTitle else playlist.title,
                    details = "$trackCount трек${getNumericPostfix(trackCount)}\n" +
                        formatDuration(playlist.durationMs ?: 0),
                    shouldLoadCover = !isLikedPlaylist && !playlist.ogImageUri.isNullOrBlank(),
                    fallbackCoverResId = if (isLikedPlaylist) null else R.drawable.ic_player_play_v2,
                    artworkResId = if (isLikedPlaylist) R.drawable.ic_playlist_liked else null,
                    highlighted = pickedTrackId != null &&
                        playlist.tracks.any { it.trackId == pickedTrackId },
                ),
            )
        }
}

/** Собирает те же UI-модели для локальных плейлистов. */
private fun buildLocalItems(
    playlists: List<LocalPlaylistSummary>,
    showCreateAction: Boolean,
    createTitle: String,
    dwijLabel: String,
    mediaStoreLabel: String,
    m3uLabel: String,
): List<PlaylistGridScreenItem> = buildList {
    if (showCreateAction) {
        add(
            PlaylistGridScreenItem(
                id = CREATE_ITEM_ID,
                title = createTitle,
                artworkResId = R.drawable.ic_playlist_create,
                isCreateAction = true,
            ),
        )
    }
    playlists.forEach { summary ->
        val playlist = summary.playlist
        val formatLabel = when (playlist.origin) {
            LocalPlaylistOrigin.DWIJ.name -> dwijLabel
            LocalPlaylistOrigin.MEDIA_STORE.name -> mediaStoreLabel
            else -> m3uLabel
        }
        val duration = formatDuration(
            summary.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
        add(
            PlaylistGridScreenItem(
                id = playlist.playlistId,
                title = playlist.name,
                fallbackCoverResId = R.drawable.ic_player_play_v2,
                details = "${summary.trackCount} " +
                    "трек${getNumericPostfix(summary.trackCount)} · $duration\n$formatLabel",
            ),
        )
    }
}

private fun elapsedMillis(startedNanos: Long): Long =
    (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000L

private const val CREATE_ITEM_ID = "playlist_create_item"
private const val TAG = "PlaylistGridRoute"
