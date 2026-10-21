package com.yellastrodev.dwij.navigation

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yellastrodev.dwij.HomeMusicSource
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.repo.VK_MY_TRACKS
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.models.GridPlaylistModel
import com.yellastrodev.dwij.models.VkPlaylistGridModel
import com.yellastrodev.dwij.ui.playlist.*
import com.yellastrodev.dwij.ui.toImageBitmapOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Показывает подряд локальные, Яндекс- и VK-плейлисты, открывая каждый в своём источнике. */
@Composable
internal fun AllPlaylistGridRoute(
    component: DwijComponent,
    platform: PlaylistGridPlatform,
    texts: PlaylistGridTexts,
    onOpenLocalPlaylist: (String) -> Unit,
    onOpenYandexPlaylist: (String) -> Unit,
    onOpenVkPlaylist: (String) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val yandexModel = viewModel(key = "all-playlists-yandex") {
        GridPlaylistModel(component.playlistRepository, component.trackRepository, component.coverRepository)
    }
    val vkRepository = component.vkMusicRepository
    val vkModel = viewModel(key = "all-playlists-vk") { VkPlaylistGridModel(vkRepository, null) }
    val yandexPlaylists by yandexModel.playlists.collectAsState()
    val yandexLoaded by yandexModel.initialLoadComplete.collectAsState()
    val localPlaylists by component.localMusicRepository.playlistSummaries.collectAsState(initial = null)
    val localSyncing by component.localMusicRepository.isSynchronizing.collectAsState()
    val vkPlaylists by vkRepository.playlists.collectAsState()
    val savedVkPlaylists by vkRepository.savedPlaylists.collectAsState()
    val vkAuthorized by vkRepository.authorized.collectAsState()
    val vkSession by vkRepository.sessionRevision.collectAsState()
    val myVkTracks by vkRepository.myTracks.collectAsState()
    val myVkTracksLoaded by vkRepository.myTracksLoaded.collectAsState()
    val vkState by vkModel.state.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<PlaylistGridMessageEvent?>(null) }

    LaunchedEffect(vkAuthorized, vkSession) { vkModel.load(vkAuthorized, vkSession) }
    LaunchedEffect(platform) {
        if (platform.hasLocalMusicAccess()) platform.startLocalLibrarySync()
    }
    LaunchedEffect(vkState.error) {
        if (vkState.error != null) {
            message = PlaylistGridMessageEvent((message?.id ?: 0L) + 1, PlaylistGridMessage.RefreshFailed)
        }
    }

    val visibleVkPlaylists = if (vkAuthorized) vkPlaylists else savedVkPlaylists
    val showMyVkTracks = vkAuthorized || savedVkPlaylists.any { it.fullId == VK_MY_TRACKS }
    val items = remember(localPlaylists, yandexPlaylists, visibleVkPlaylists, texts, showMyVkTracks, myVkTracks.size, myVkTracksLoaded) {
        buildLocalItems(localPlaylists.orEmpty(), false, texts.create, texts.localDwij, texts.localMediaStore, texts.localM3u)
            .map { it.copy(id = "local:${it.id}") } +
            buildYandexItems(yandexPlaylists, null, false, texts.create, texts.liked)
                .map { it.copy(id = "yandex:${it.id}") } +
            (if (showMyVkTracks) listOf(
                PlaylistGridScreenItem(
                    id = "vk:$VK_MY_TRACKS",
                    title = "Мои треки",
                    details = if (myVkTracksLoaded || myVkTracks.isNotEmpty()) "${myVkTracks.size} треков" else "",
                    source = HomeMusicSource.Vk,
                    artwork = PlaylistGridArtwork.Liked,
                ),
            ) else emptyList()) +
            visibleVkPlaylists.filter { it.id >= 0 && it.fullId != VK_MY_TRACKS }.map { playlist ->
                PlaylistGridScreenItem(
                    id = "vk:${playlist.fullId}",
                    title = playlist.title,
                    details = "${playlist.count} треков",
                    source = HomeMusicSource.Vk,
                    shouldLoadCover = playlist.coverUrl != null,
                    fallbackArtwork = PlaylistGridArtwork.PlayerFallback,
                )
            }
    }

    val state = PlaylistGridRouteState(
        title = texts.title,
        items = items,
        selectedSource = HomeMusicSource.All,
        showSourceSelector = true,
        emptyMessage = texts.yandexEmpty,
        isLoading = items.isEmpty() && (!yandexLoaded || vkState.loading || localPlaylists == null),
        isRefreshing = refreshing || vkState.refreshing || localSyncing,
        dialog = null,
        message = message,
    )
    val actions = PlaylistGridRouteActions(
        onSourceSelected = { source ->
            scope.launch {
                if (source != HomeMusicSource.Local || platform.hasLocalMusicAccess() || platform.requestLocalMusicAccess()) {
                    component.musicSourceSelectionStore.select(source)
                    if (source == HomeMusicSource.Local) platform.startLocalLibrarySync()
                }
            }
        },
        onBackClick = onBackClick,
        onItemClick = { item ->
            val playlistId = item.id.substringAfter(':')
            when (item.source) {
                HomeMusicSource.Local -> onOpenLocalPlaylist(playlistId)
                HomeMusicSource.Yandex -> onOpenYandexPlaylist(playlistId)
                HomeMusicSource.Vk -> onOpenVkPlaylist(playlistId)
                else -> Unit
            }
        },
        onItemLongClick = {},
        loadCover = { id ->
            withContext(Dispatchers.IO) {
                val playlistId = id.substringAfter(':')
                when {
                    id.startsWith("yandex:") -> yandexPlaylists.firstOrNull { it.getdId() == playlistId }
                        ?.let { yandexModel.getCover(it)?.toImageBitmapOrNull() }
                    id.startsWith("vk:") -> visibleVkPlaylists.firstOrNull { it.fullId == playlistId }
                        ?.let { component.coverRepository.getVkPlaylistCover(it)?.toImageBitmapOrNull() }
                    else -> null
                }
            }
        },
        onRefresh = {
            if (!refreshing) {
                if (platform.hasLocalMusicAccess()) platform.startLocalLibrarySync()
                if (vkAuthorized) vkModel.refresh()
                refreshing = true
                scope.launch {
                    try {
                        if (yandexModel.refreshPlaylists() is DataResult.Failure) {
                            message = PlaylistGridMessageEvent((message?.id ?: 0L) + 1, PlaylistGridMessage.RefreshFailed)
                        }
                    } finally {
                        refreshing = false
                    }
                }
            }
        },
        onDialogDismiss = {},
        onCreatePlaylist = { _, _ -> },
        onRemoveTrackConfirm = {},
        onDeleteInfoConfirm = {},
    )
    PlaylistGridContent(state, actions, modifier)
}
