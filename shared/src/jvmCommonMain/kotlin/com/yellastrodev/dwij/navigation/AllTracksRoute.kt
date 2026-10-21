package com.yellastrodev.dwij.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yellastrodev.dwij.TrackListItemUiModel
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.models.AllTracksModel
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.models.TracklistModel
import com.yellastrodev.dwij.models.VkPlaylistModel
import com.yellastrodev.dwij.data.repo.VK_ALL_TRACKS
import com.yellastrodev.dwij.resources.*
import com.yellastrodev.dwij.ui.ObjectScreen
import com.yellastrodev.dwij.ui.playlist.PlaylistGridPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Показывает перемешанное объединение тех же списков, что отдельные разделы трёх источников. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
internal fun AllTracksRoute(
    component: DwijComponent,
    playerModel: PlayerModel,
    playlistPlatform: PlaylistGridPlatform,
    onBackClick: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val yandexModel = viewModel(key = "all-tracks-yandex") {
        TracklistModel(
            playlistRepo = component.playlistRepository,
            coverRepo = component.coverRepository,
            trackRepo = component.trackRepository,
            trackCacheRepo = component.trackCacheRepo,
            songRepo = component.songRepository,
            playerRepo = component.playerRepo,
            waveRepository = component.waveRepository,
            logger = component.logger,
        )
    }
    val vkRepository = component.vkMusicRepository
    val vkModel = viewModel(key = "all-tracks-vk") { VkPlaylistModel(vkRepository, VK_ALL_TRACKS) }
    val model = viewModel(key = "all-tracks") {
        AllTracksModel(
            localSongs = component.localMusicRepository.songs,
            yandexSongs = yandexModel.tracks,
            vkSongs = vkModel.state.map { it.tracks }.distinctUntilChanged()
                .mapLatest { component.songRepository.songsForVkTracks(it) }.flowOn(Dispatchers.IO),
            player = component.playerRepo,
            wave = component.waveRepository,
        )
    }
    val entries by model.entries.collectAsState()
    val ready by model.ready.collectAsState()
    val playing by model.playing.collectAsState()
    val yandexLoading by yandexModel.isLoading.collectAsState()
    val cachedUnavailableSongIds by yandexModel.cachedUnavailableSongIds.collectAsState()
    val vkState by vkModel.state.collectAsState()
    val vkAuthorized by vkRepository.authorized.collectAsState()
    val vkSession by vkRepository.sessionRevision.collectAsState()
    val vkMembership by vkRepository.membershipRevision.collectAsState()
    val localSyncing by component.localMusicRepository.isSynchronizing.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val title = stringResource(Res.string.all_tracks_title)
    val loadFailed = stringResource(Res.string.all_tracks_load_failed)

    LaunchedEffect(yandexModel) { yandexModel.setType(DwijDestination.OBJECT_TYPE_TRACKLIST, "_") }
    LaunchedEffect(vkModel, vkAuthorized, vkSession, vkMembership) { vkModel.load(vkAuthorized, vkSession, vkMembership) }
    LaunchedEffect(playlistPlatform) {
        if (playlistPlatform.hasLocalMusicAccess()) playlistPlatform.startLocalLibrarySync()
    }
    LaunchedEffect(vkState.error) {
        vkState.error?.let { snackbar.showSnackbar(it.vkPlaylistMessage()) }
    }

    /** Передаёт выбранную песню из показанного снимка и сообщает об ошибке запуска. */
    fun play(songId: String?) {
        if (playing) return
        scope.launch {
            try {
                if (model.play(songId, title)) onOpenPlayer()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                component.logger.error("AllTracksRoute", "[play] Не удалось запустить общую фонотеку", error)
                snackbar.showSnackbar(loadFailed)
            }
        }
    }

    val songsById = remember(entries) { entries.associate { it.song.id to it.song } }
    val items = remember(entries, cachedUnavailableSongIds) {
        entries.map { (song, _) ->
            TrackListItemUiModel(
                key = song.id,
                trackId = song.id,
                title = song.title,
                artist = song.artistNames.joinToString(", "),
                hasMultipleSources = song.instances.size > 1,
                hasUnresolvedMatchCandidate = song.hasPendingMatchCandidate,
                isLocalOnlyInLibrary = song.isLocalOnlyInLibrary,
                yandexTrackId = song.yandexInstances.firstOrNull()?.track?.id,
                isYandexUnavailable = song.yandexInstances.isNotEmpty() && song.yandexInstances.none { it.track.available },
                isPlaybackBlocked = song.localInstances.isEmpty() && song.vkInstances.isEmpty() &&
                    song.yandexInstances.none { it.track.available } && song.id !in cachedUnavailableSongIds,
            )
        }
    }
    Box(modifier.fillMaxSize()) {
        ObjectScreen(
            title = title,
            subtitle = pluralStringResource(Res.plurals.object_track_count, items.size, items.size),
            description = null,
            cover = null,
            tracks = items,
            onBackClick = onBackClick,
            onPlayClick = { play(null) },
            onTrackClick = { _, item -> play(item.trackId) },
            loadTrackCover = { id -> songsById[id]?.let { playerModel.cover(it, maxEdgePx = 180).firstOrNull() } },
            showShare = false,
            showWave = false,
            emptyMessage = stringResource(Res.string.all_tracks_empty),
            isLoading = items.isEmpty() && (!ready || yandexLoading || vkState.isLoading || localSyncing),
            isRefreshing = refreshing || vkState.isRefreshing || localSyncing,
            onRefresh = {
                if (!refreshing && !vkState.isRefreshing) {
                    refreshing = true
                    vkModel.refresh()
                    if (playlistPlatform.hasLocalMusicAccess()) playlistPlatform.startLocalLibrarySync()
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) { component.playlistRepository.refreshPlaylists() }
                            if (result is DataResult.Failure) snackbar.showSnackbar(loadFailed)
                        } finally { refreshing = false }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
}
