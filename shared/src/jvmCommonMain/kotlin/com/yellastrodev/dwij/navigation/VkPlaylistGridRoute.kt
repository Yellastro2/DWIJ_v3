package com.yellastrodev.dwij.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yellastrodev.dwij.HomeMusicSource
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.repo.VK_MY_TRACKS
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.models.VkPlaylistGridModel
import com.yellastrodev.dwij.resources.*
import com.yellastrodev.dwij.ui.playlist.*
import com.yellastrodev.dwij.ui.theme.DwijColors
import com.yellastrodev.dwij.ui.toImageBitmapOrNull
import com.yellastrodev.vkmusicsdk.VkAudio
import com.yellastrodev.vkmusicsdk.VkPlaylist
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.getString

/** Показывает удерживаемую сетку и offline-обложки, отмечает членство и подтверждает удаление трека. */
@Composable
internal fun VkPlaylistGridRoute(
    component: DwijComponent,
    platform: PlaylistGridPlatform,
    onOpenPlaylist: (String) -> Unit,
    onBackClick: () -> Unit,
    trackToAdd: String?,
    onRequestLocalTrackDownloads: (List<LocalTrackDownloadRequest>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repository = component.vkMusicRepository
    val playlists by repository.playlists.collectAsState()
    val savedPlaylists by repository.savedPlaylists.collectAsState()
    val userId by repository.userId.collectAsState()
    val authorized by repository.authorized.collectAsState()
    val sessionRevision by repository.sessionRevision.collectAsState()
    val myTracks by repository.myTracks.collectAsState()
    val myTracksLoaded by repository.myTracksLoaded.collectAsState()
    val playlistTracks by repository.playlistTracks.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val model = viewModel(key = "vk-playlist-grid:${trackToAdd ?: "browse"}") {
        VkPlaylistGridModel(repository, trackToAdd)
    }
    val gridState by model.state.collectAsState()
    val loading = gridState.loading
    val refreshing = gridState.refreshing
    val pickedTrack = gridState.pickedTrack
    val loadError = gridState.error?.vkPlaylistMessage()
        ?: if (!authorized && savedPlaylists.isEmpty()) "Войдите в ВК Музыку в настройках" else null
    var busy by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<VkPlaylist?>(null) }
    var contextPlaylist by remember { mutableStateOf<VkPlaylist?>(null) }
    var removing by remember(sessionRevision) { mutableStateOf<Pair<VkPlaylist, VkAudio>?>(null) }

    /** Показывает безопасный код ошибки API и сохраняет доступ к повторной попытке. */
    fun showFailure(error: DataError) {
        scope.launch { snackbar.showSnackbar(error.vkPlaylistMessage()) }
    }

    LaunchedEffect(gridState.error) {
        gridState.error?.let { snackbar.showSnackbar(it.vkPlaylistMessage()) }
    }

    LaunchedEffect(authorized, sessionRevision, trackToAdd) {
        creating = false
        deleting = null
        removing = null
        contextPlaylist = null
        model.load(authorized, sessionRevision)
    }

    val visiblePlaylists = if (!authorized && trackToAdd == null) savedPlaylists
        else if (!authorized) emptyList() else if (trackToAdd == null) playlists else playlists.filter { it.canEdit(userId) }
    val createTitle = stringResource(Res.string.playlists_create)
    val items = buildList {
        if (trackToAdd == null && authorized && userId != null) {
            add(PlaylistGridScreenItem(id = "vk:create", title = createTitle,
                artwork = PlaylistGridArtwork.Create, isCreateAction = true))
        }
        if (trackToAdd == null && (authorized || savedPlaylists.any { it.fullId == VK_MY_TRACKS })) {
            add(PlaylistGridScreenItem(id = VK_MY_TRACKS, title = "Мои треки",
                details = if (myTracksLoaded || myTracks.isNotEmpty()) "${myTracks.size} треков" else "",
                artwork = PlaylistGridArtwork.Liked))
        }
        visiblePlaylists.filter { it.id >= 0 }.forEach { playlist ->
            add(PlaylistGridScreenItem(id = playlist.fullId, title = playlist.title,
                details = "${playlist.count} треков", shouldLoadCover = playlist.coverUrl != null,
                highlighted = pickedTrack?.let { audio ->
                    repository.audioIdentityIds(audio).any { it in playlistTracks[playlist.fullId].orEmpty() }
                } == true,
                fallbackArtwork = PlaylistGridArtwork.PlayerFallback))
        }
    }
    val state = PlaylistGridRouteState(
        title = stringResource(if (trackToAdd == null) Res.string.playlists_title else Res.string.playlists_add_track_title),
        items = items, selectedSource = HomeMusicSource.Vk, showSourceSelector = trackToAdd == null,
        emptyMessage = loadError ?: if (trackToAdd == null) "У вас пока нет плейлистов VK" else "Нет своих плейлистов для добавления трека",
        isLoading = loading, isRefreshing = refreshing || busy,
        dialog = if (creating) PlaylistGridDialogState.Create(HomeMusicSource.Vk, busy) else null,
        message = null,
    )
    val actions = PlaylistGridRouteActions(
        onSourceSelected = { source ->
            if (!busy && source != HomeMusicSource.Vk) {
                scope.launch {
                    if (source != HomeMusicSource.Local || platform.hasLocalMusicAccess() || platform.requestLocalMusicAccess()) {
                        component.musicSourceSelectionStore.select(source)
                        if (source == HomeMusicSource.Local) platform.startLocalLibrarySync()
                    }
                }
            }
        },
        onBackClick = onBackClick,
        onItemClick = click@{ item ->
            if (busy || loading) return@click
            if (item.isCreateAction) { creating = true; return@click }
            if (item.id == VK_MY_TRACKS && trackToAdd == null) { onOpenPlaylist(VK_MY_TRACKS); return@click }
            val playlist = visiblePlaylists.firstOrNull { it.fullId == item.id } ?: return@click
            if (trackToAdd == null) onOpenPlaylist(playlist.fullId) else {
                val audio = pickedTrack
                if (audio == null) {
                    scope.launch { snackbar.showSnackbar(getString(Res.string.playlists_track_load_failed)) }
                    return@click
                }
                busy = true
                scope.launch {
                    try {
                        // Проверяем именно выбранный список: неизвестный или устаревший кеш не означает отсутствия трека.
                        when (val content = repository.getPlaylist(playlist.fullId, allowSavedFallback = false)) {
                            is DataResult.Failure -> showFailure(content.error)
                            is DataResult.Success -> {
                                val ids = repository.audioIdentityIds(audio)
                                val existing = content.value.tracks.firstOrNull { candidate ->
                                    repository.audioIdentityIds(candidate).any { it in ids }
                                }
                                if (existing != null) removing = content.value.playlist to existing
                                else when (val result = repository.addToPlaylist(content.value.playlist, audio)) {
                                    is DataResult.Success -> onBackClick()
                                    is DataResult.Failure -> showFailure(result.error)
                                }
                            }
                        }
                    } finally { busy = false }
                }
            }
        },
        onItemLongClick = { item ->
            if (!busy && trackToAdd == null) {
                contextPlaylist = visiblePlaylists.firstOrNull { it.fullId == item.id }
            }
        },
        loadCover = { fullId ->
            withContext(Dispatchers.IO) {
                visiblePlaylists.firstOrNull { it.fullId == fullId }
                    ?.let { component.coverRepository.getVkPlaylistCover(it)?.toImageBitmapOrNull() }
            }
        },
        onRefresh = { if (!refreshing && !busy && authorized) model.refresh() },
        onDialogDismiss = { if (!busy) creating = false },
        onCreatePlaylist = { title, _ ->
            if (!busy && title.isNotBlank()) {
                busy = true
                scope.launch {
                    try {
                        when (val result = repository.createPlaylist(title)) {
                            is DataResult.Success -> { creating = false; onOpenPlaylist(result.value.fullId) }
                            is DataResult.Failure -> showFailure(result.error)
                        }
                    } finally { busy = false }
                }
            }
        },
        onRemoveTrackConfirm = {}, onDeleteInfoConfirm = {},
    )
    Box(modifier.fillMaxSize()) {
        PlaylistGridContent(state, actions, Modifier.fillMaxSize())
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
    removing?.let { (playlist, audio) ->
        VkPlaylistConfirmDialog(
            title = "Удалить трек из плейлиста?",
            text = "«${audio.title}» уже есть в «${playlist.title}». Удалить его из этого плейлиста?",
            busy = busy, onDismiss = { removing = null },
            onConfirm = {
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            when (val result = repository.removeFromPlaylist(playlist, audio)) {
                                is DataResult.Success -> { removing = null; onBackClick() }
                                is DataResult.Failure -> showFailure(result.error)
                            }
                        } finally { busy = false }
                    }
                }
            },
        )
    }
    contextPlaylist?.let { playlist ->
        AlertDialog(onDismissRequest = { if (!busy) contextPlaylist = null }, containerColor = DwijColors.Background,
            title = { Text(playlist.title, color = DwijColors.White) },
            text = {
                Column {
                    TextButton(modifier = Modifier.fillMaxWidth(), enabled = !busy, onClick = {
                        contextPlaylist = null
                        busy = true
                        scope.launch {
                            try {
                                when (val content = repository.getPlaylist(playlist.fullId)) {
                                    is DataResult.Failure -> showFailure(content.error)
                                    is DataResult.Success -> {
                                        val requests = prepareVkPlaylistLocalDownloads(component, content.value.playlist, content.value.tracks)
                                        if (requests.isNotEmpty()) onRequestLocalTrackDownloads(requests)
                                        snackbar.showSnackbar(if (requests.isEmpty()) getString(Res.string.playlist_save_locally_nothing)
                                            else getString(Res.string.playlist_save_locally_queued, requests.size))
                                    }
                                }
                            } catch (error: kotlinx.coroutines.CancellationException) { throw error
                            } catch (error: Exception) {
                                component.logger.warning("VkPlaylistGridRoute", "[saveVkPlaylistLocally] Не удалось подготовить загрузку: ${error.javaClass.simpleName}")
                                snackbar.showSnackbar(getString(Res.string.playlist_save_locally_failed))
                            } finally { busy = false }
                        }
                    }) { Text(stringResource(Res.string.track_save_locally), color = DwijColors.Cyan) }
                    if (playlist.canDelete(userId)) {
                        TextButton(modifier = Modifier.fillMaxWidth(), enabled = !busy,
                            onClick = { contextPlaylist = null; deleting = playlist }) {
                            Text("Удалить плейлист", color = DwijColors.Pink)
                        }
                    }
                }
            }, confirmButton = {
                TextButton(onClick = { contextPlaylist = null }) { Text("Закрыть", color = DwijColors.White) }
            })
    }
    deleting?.let { playlist ->
        VkPlaylistConfirmDialog(
            title = "Удалить плейлист?", text = "Плейлист «${playlist.title}» будет удалён из VK.",
            busy = busy, onDismiss = { deleting = null },
            onConfirm = {
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            when (val result = repository.deletePlaylist(playlist)) {
                                is DataResult.Success -> deleting = null
                                is DataResult.Failure -> showFailure(result.error)
                            }
                        } finally { busy = false }
                    }
                }
            },
        )
    }
}

/** Подтверждение операции VK с контрастными текстами и запретом повторной отправки. */
@Composable
internal fun VkPlaylistConfirmDialog(
    title: String, text: String, busy: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() }, containerColor = DwijColors.Background,
        title = { Text(title, color = DwijColors.White) }, text = { Text(text, color = DwijColors.White) },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !busy) { Text("Удалить", color = DwijColors.Pink) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(Res.string.playlists_cancel), color = DwijColors.Cyan) } },
    )
}

/** Возвращает безопасное сообщение VK, не используя серверные тексты или exception с URL. */
internal fun DataError.vkPlaylistMessage(): String = when (this) {
    DataError.Unauthorized -> "Войдите в ВК Музыку в настройках"
    is DataError.Remote -> "VK не выполнил запрос (код ${code ?: statusCode.toString()}). Повторите попытку"
    else -> "Не удалось выполнить запрос VK. Повторите попытку"
}
