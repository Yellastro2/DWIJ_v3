package com.yellastrodev.dwij.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yellastrodev.dwij.TrackListItemUiModel
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.TrackInstance
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.models.VkPlaylistModel
import com.yellastrodev.dwij.resources.*
import com.yellastrodev.dwij.ui.ObjectScreen
import com.yellastrodev.dwij.ui.toImageBitmapOrNull
import com.yellastrodev.vkmusicsdk.VkAudio
import com.yellastrodev.vkmusicsdk.VkPlaylist
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.getString

/** Показывает удерживаемый состав VK и общие признаки источников/совпадений из индекса Song. */
@Composable
internal fun VkPlaylistRoute(
    component: DwijComponent,
    playlistId: String,
    onBackClick: () -> Unit,
    onOpenPlayer: () -> Unit,
    onAddToPlaylist: (String) -> Unit,
    onRequestLocalTrackDownload: (String, String) -> Unit,
    onRequestLocalTrackDownloads: (List<LocalTrackDownloadRequest>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = component.vkMusicRepository
    val userId by repository.userId.collectAsState()
    val authorized by repository.authorized.collectAsState()
    val sessionRevision by repository.sessionRevision.collectAsState()
    val localRevision by repository.localStorageRevision.collectAsState()
    val downloads by repository.localDownloads.collectAsState()
    val myTracks by repository.myTracks.collectAsState()
    val myTracksLoaded by repository.myTracksLoaded.collectAsState()
    val membershipRevision by repository.membershipRevision.collectAsState()
    val factory = remember(repository, playlistId) { VkPlaylistModel.Factory(repository, playlistId) }
    val model = viewModel<VkPlaylistModel>(key = "vk-playlist:$playlistId", factory = factory)
    val state by model.state.collectAsState()
    val indexedSongs by component.songRepository.vkSongs.collectAsState(initial = emptyList())
    val songsByVkId = remember(indexedSongs) {
        buildMap {
            indexedSongs.forEach { song ->
                song.instances.filterIsInstance<TrackInstance.Vk>().forEach { put(it.track.fullId, song) }
            }
        }
    }
    val playlist = state.playlist
    val tracks = state.tracks
    val loading = state.isLoading
    val refreshing = state.isRefreshing
    val loadError = state.error?.vkPlaylistMessage()
    var savedIds by remember { mutableStateOf(emptySet<String>()) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var cover by remember(playlistId, sessionRevision) { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var removeTrack by remember { mutableStateOf<VkAudio?>(null) }
    var deletePlaylist by remember { mutableStateOf(false) }

    /** Отображает безопасную ошибку операции; URL и тексты VK в snackbar не передаются. */
    fun showFailure(error: DataError) {
        scope.launch { snackbar.showSnackbar(error.vkPlaylistMessage()) }
    }

    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it.vkPlaylistMessage()) }
    }

    LaunchedEffect(playlist?.coverUrl) {
        cover = playlist?.let { current -> withContext(Dispatchers.IO) {
            component.coverRepository.getVkPlaylistCover(current)?.toImageBitmapOrNull()
        } }
    }

    LaunchedEffect(model, authorized, sessionRevision, membershipRevision) {
        model.load(authorized, sessionRevision, membershipRevision)
    }

    LaunchedEffect(playlistId, authorized, sessionRevision) {
        removeTrack = null
        deletePlaylist = false
    }

    /** Запускает весь снимок плейлиста с выбранного индекса, сохраняя дубли и исходный порядок. */
    fun play(index: Int) {
        val sourcePlaylist = playlist ?: return
        val queue = tracks
        if (busy || loading || index !in queue.indices) return
        busy = true
        scope.launch {
            try {
                when (val result = repository.playPlaylist(sourcePlaylist, queue, index, component.playerRepo)) {
                    is DataResult.Success -> onOpenPlayer()
                    is DataResult.Failure -> showFailure(result.error)
                }
            } finally { busy = false }
        }
    }

    LaunchedEffect(tracks, localRevision) {
        savedIds = withContext(Dispatchers.IO) { tracks.filter { repository.isSavedLocally(it.fullId) }.map { it.fullId }.toSet() }
    }
    val items = tracks.mapIndexed { index, audio ->
        val song = songsByVkId[audio.fullId]
        TrackListItemUiModel(key = "${audio.fullId}:$index", trackId = audio.fullId,
            title = audio.title, artist = audio.artistNames.joinToString(", "), shouldLoadCover = audio.coverUrl != null,
            hasMultipleSources = (song?.instances?.size ?: 0) > 1,
            hasUnresolvedMatchCandidate = song?.hasPendingMatchCandidate == true,
            isSavedLocally = audio.fullId in savedIds, isSavingLocally = "vk:${audio.fullId}" in downloads)
    }
    Box(modifier.fillMaxSize()) {
        ObjectScreen(
            title = playlist?.title ?: stringResource(Res.string.object_loading_title),
            subtitle = pluralStringResource(Res.plurals.object_track_count, tracks.size, tracks.size),
            description = playlist?.description?.takeIf(String::isNotBlank), cover = cover, tracks = items,
            onBackClick = onBackClick, onPlayClick = { play(0) },
            onTrackClick = { index, _ -> play(index) },
            objectMenuContent = { dismiss ->
                DropdownMenuItem(text = { Text(stringResource(Res.string.track_save_locally)) },
                    enabled = !busy && !loading && playlist != null && tracks.isNotEmpty(),
                    onClick = {
                        dismiss()
                        val current = playlist ?: return@DropdownMenuItem
                        busy = true
                        scope.launch {
                            try {
                                val requests = prepareVkPlaylistLocalDownloads(component, current, tracks)
                                if (requests.isNotEmpty()) onRequestLocalTrackDownloads(requests)
                                snackbar.showSnackbar(if (requests.isEmpty()) getString(Res.string.playlist_save_locally_nothing)
                                    else getString(Res.string.playlist_save_locally_queued, requests.size))
                            } catch (error: kotlinx.coroutines.CancellationException) { throw error
                            } catch (error: Exception) {
                                component.logger.warning("VkPlaylistRoute", "[saveVkPlaylistLocally] Не удалось подготовить загрузку: ${error.javaClass.simpleName}")
                                snackbar.showSnackbar(getString(Res.string.playlist_save_locally_failed))
                            } finally { busy = false }
                        }
                    })
                if (playlist?.canDelete(userId) == true) {
                    DropdownMenuItem(text = { Text("Удалить плейлист") }, enabled = !busy && !loading,
                        onClick = { dismiss(); deletePlaylist = true })
                }
                DropdownMenuItem(text = { Text("Обновить") }, enabled = !busy && !loading && authorized,
                    onClick = { dismiss(); if (!refreshing) model.refresh() })
            },
            trackContextMenuContent = { index, item, dismiss ->
                val audio = tracks.getOrNull(index)
                DropdownMenuItem(text = { Text(stringResource(when {
                    item.isSavedLocally -> Res.string.track_saved_locally
                    item.isSavingLocally -> Res.string.track_saving_locally
                    else -> Res.string.track_save_locally
                })) }, enabled = audio != null && !item.isSavedLocally && !item.isSavingLocally,
                    onClick = {
                        dismiss()
                        audio?.let {
                            repository.rememberDownloadAudio(it)
                            onRequestLocalTrackDownload("vk:${it.fullId}", it.title)
                        }
                    })
                DropdownMenuItem(text = { Text(stringResource(Res.string.player_add_to_playlist)) },
                    enabled = audio != null && !busy && !loading && authorized,
                    onClick = { dismiss(); audio?.let { onAddToPlaylist("vk:${it.requestId}") } })
                val inMyTracks = remember(audio, myTracks, membershipRevision) {
                    audio?.let(repository::isInMyTracks) == true
                }
                DropdownMenuItem(text = { Text(if (inMyTracks) "Удалить из «Моих треков»" else "Добавить в «Мои треки»") },
                    enabled = audio != null && authorized && myTracksLoaded && !busy && !loading,
                    onClick = {
                        dismiss()
                        val selected = audio ?: return@DropdownMenuItem
                        busy = true
                        scope.launch {
                            try {
                                when (val result = repository.setInMyTracks(selected, !inMyTracks)) {
                                    is DataResult.Success -> Unit
                                    is DataResult.Failure -> showFailure(result.error)
                                }
                            } finally { busy = false }
                        }
                    })
                if (playlist?.canEdit(userId) == true) {
                    DropdownMenuItem(text = { Text("Удалить из плейлиста") }, enabled = audio != null && !busy && !loading,
                        onClick = { dismiss(); removeTrack = audio })
                }
            },
            loadTrackCover = { id -> withContext(Dispatchers.IO) {
                tracks.firstOrNull { it.fullId == id }
                    ?.let { component.coverRepository.getVkTrackCover(it)?.toImageBitmapOrNull() }
            } },
            showShare = false, showWave = false, isLoading = loading, isRefreshing = busy || refreshing,
            emptyMessage = loadError ?: stringResource(Res.string.track_list_empty),
            onRefresh = { if (!refreshing && !busy && authorized) model.refresh() },
            modifier = Modifier.fillMaxSize(),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
    val currentPlaylist = playlist
    removeTrack?.let { audio ->
        VkPlaylistConfirmDialog(
            title = "Удалить трек из плейлиста?",
            text = "«${audio.title}» будет удалён из этого плейлиста. Трек останется в вашей музыке.",
            busy = busy, onDismiss = { removeTrack = null },
            onConfirm = {
                if (currentPlaylist != null && !busy) {
                    busy = true
                    scope.launch {
                        try {
                            when (val result = repository.removeFromPlaylist(currentPlaylist, audio)) {
                                is DataResult.Success -> {
                                    removeTrack = null
                                    model.removeTrack(audio.fullId)
                                }
                                is DataResult.Failure -> showFailure(result.error)
                            }
                        } finally { busy = false }
                    }
                }
            },
        )
    }
    if (deletePlaylist && currentPlaylist != null) {
        VkPlaylistConfirmDialog(title = "Удалить плейлист?",
            text = "Плейлист «${currentPlaylist.title}» будет удалён из VK.", busy = busy,
            onDismiss = { deletePlaylist = false },
            onConfirm = {
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            when (val result = repository.deletePlaylist(currentPlaylist)) {
                                is DataResult.Success -> { deletePlaylist = false; onBackClick() }
                                is DataResult.Failure -> showFailure(result.error)
                            }
                        } finally { busy = false }
                    }
                }
            },
        )
    }
}

/** Сохраняет offline-снимок и формирует уникальную очередь, пропуская готовые и активные треки. */
internal suspend fun prepareVkPlaylistLocalDownloads(component: DwijComponent, playlist: VkPlaylist,
    tracks: List<VkAudio>): List<LocalTrackDownloadRequest> = withContext(Dispatchers.IO) {
    val repository = component.vkMusicRepository
    repository.rememberLocalPlaylist(playlist, tracks)
    tracks.distinctBy { it.fullId }.filter {
        !repository.isSavedLocally(it.fullId) && "vk:${it.fullId}" !in repository.localDownloads.value
    }.map { LocalTrackDownloadRequest("vk:${it.fullId}", it.title) }
}
