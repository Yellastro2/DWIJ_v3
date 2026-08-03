package com.yellastrodev.dwij.navigation

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yellastrodev.dwij.MultiSourceDialog
import com.yellastrodev.dwij.ObjectScreen
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.TrackListItemUiModel
import com.yellastrodev.dwij.TrackSourceIndicator
import com.yellastrodev.dwij.TrackSourceOptionUiModel
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.SongMatchCandidateEntity
import com.yellastrodev.dwij.data.entities.TrackInstance
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.models.TracklistModel
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Compose-route абстрактного музыкального объекта и его списка треков. */
@Composable
fun ObjectRoute(
    navController: NavHostController,
    playerModel: PlayerModel,
    objectType: String,
    objectValue: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val application = context.applicationContext as yApplication
    val songMatchRepository = application.songMatchRepository
    val songRepository = application.songRepository
    val model = viewModel<TracklistModel>(
        factory = TracklistModel.Factory(
            repo = application.playlistRepository,
            coverRepo = application.coverRepository,
            trackRepo = application.trackRepository,
            trackCacheRepo = application.trackCacheRepo,
            songRepo = application.songRepository,
            playerRepo = application.playerRepo,
            waveRepo = application.waveRepository,
        ),
    )
    val playlist by model.playlist.collectAsState()
    val tracks by model.tracks.collectAsState()
    val isLoading by model.isLoading.collectAsState()
    val cachedUnavailableSongIds by model.cachedUnavailableSongIds.collectAsState()
    val yandexPlaylist = playlist as? dYaPlaylist
    val objectId = yandexPlaylist?.playlistUuid
    val unknownArtist = stringResource(R.string.home_player_unknown_artist)
    val title = when {
        objectType == DwijDestination.OBJECT_TYPE_TRACKLIST ->
            stringResource(R.string.track_list_all_title)
        yandexPlaylist != null -> yandexPlaylist.title
        else -> stringResource(R.string.object_loading_title)
    }
    val count = yandexPlaylist?.trackCount ?: tracks.size
    val subtitle = pluralStringResource(R.plurals.object_track_count, count, count)
    val trackItems = remember(tracks, cachedUnavailableSongIds, unknownArtist) {
        tracks.toObjectTrackListItems(unknownArtist, cachedUnavailableSongIds)
    }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var objectCover by remember(objectId) { mutableStateOf<ImageBitmap?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var sourceDialogSongId by remember { mutableStateOf<String?>(null) }
    var candidateSongs by remember(sourceDialogSongId) { mutableStateOf(emptyList<Song>()) }
    var isMergingSources by remember(sourceDialogSongId) { mutableStateOf(false) }
    var mergeSourcesError by remember(sourceDialogSongId) { mutableStateOf<String?>(null) }
    val sourceDialogSong = remember(tracks, sourceDialogSongId) {
        tracks.firstOrNull { song -> song.id == sourceDialogSongId }
    }
    val pendingMatchCandidates by remember(sourceDialogSongId) {
        sourceDialogSongId?.let(songMatchRepository::pendingCandidatesForSong)
            ?: flowOf(emptyList<SongMatchCandidateEntity>())
    }.collectAsState(initial = emptyList())

    fun showSnackbar(message: String) {
        coroutineScope.launch { snackbarHostState.showSnackbar(message) }
    }

    LaunchedEffect(objectType, objectValue) {
        model.setType(objectType, objectValue.takeUnless { it == "_" }.orEmpty())
    }
    LaunchedEffect(sourceDialogSongId, pendingMatchCandidates) {
        val currentSongId = sourceDialogSongId ?: return@LaunchedEffect
        val relatedSongIds = pendingMatchCandidates
            .flatMap { candidate -> listOf(candidate.firstSongId, candidate.secondSongId) }
            .filterNot { songId -> songId == currentSongId }
            .distinct()
        candidateSongs = try {
            withContext(Dispatchers.IO) { songRepository.songsByIds(relatedSongIds) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(
                TAG,
                "[loadMultiSourceCandidates] Не удалось загрузить варианты " +
                    "songId=$currentSongId",
                error,
            )
            emptyList()
        }
    }

    val sourceEntries = remember(sourceDialogSong, candidateSongs) {
        (listOfNotNull(sourceDialogSong) + candidateSongs)
            .distinctBy(Song::id)
            .flatMap { song ->
                song.instances.map { instance -> ObjectSourceDialogEntry(song, instance) }
            }
            .distinctBy { entry -> entry.instance.id }
    }
    val sourceOptions = remember(sourceEntries, unknownArtist) {
        sourceEntries.map { entry ->
            TrackSourceOptionUiModel(
                instanceId = entry.instance.id,
                item = TrackListItemUiModel(
                    key = entry.instance.id,
                    trackId = entry.song.id,
                    title = entry.song.title,
                    artist = entry.song.artistNames.joinToString(", ").ifBlank { unknownArtist },
                    isYandexUnavailable =
                        (entry.instance as? TrackInstance.Yandex)?.track?.available == false,
                ),
                sourceIndicator = when (entry.instance) {
                    is TrackInstance.Yandex -> TrackSourceIndicator.YANDEX
                    is TrackInstance.Local -> TrackSourceIndicator.LOCAL
                },
            )
        }
    }
    val sourceInstancesById = remember(sourceEntries) {
        sourceEntries.associate { entry -> entry.instance.id to entry.instance }
    }

    LaunchedEffect(objectId) {
        objectCover = null
        val currentPlaylist = yandexPlaylist ?: return@LaunchedEffect
        if (currentPlaylist.ogImageUri.isNullOrBlank()) return@LaunchedEffect
        objectCover = try {
            model.getPlaylistCover(currentPlaylist).asImageBitmap()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(
                TAG,
                "[loadObjectCover] Не удалось загрузить обложку objectId=$objectId",
                error,
            )
            null
        }
    }
    LaunchedEffect(listState) {
        model.scrollResetEvents.collect { listState.scrollToItem(0) }
    }

    fun playTrack(position: Int, expectedSongId: String? = null) {
        if (model.onTrackClicked(position, expectedSongId)) {
            navController.navigate(DwijDestination.PLAYER)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        ObjectScreen(
            title = title,
            subtitle = subtitle,
            description = yandexPlaylist?.description,
            cover = objectCover,
            tracks = trackItems,
            listState = listState,
            showShare = objectType == DwijDestination.OBJECT_TYPE_PLAYLIST,
            showWave = objectType == DwijDestination.OBJECT_TYPE_PLAYLIST,
            emptyMessage = stringResource(R.string.track_list_empty),
            isLoading = isLoading,
            isRefreshing = isRefreshing,
            onRefresh = {
                if (!isRefreshing) {
                    coroutineScope.launch {
                        isRefreshing = true
                        try {
                            model.refreshObject()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Log.w(TAG, "[refreshObject] Не удалось обновить объект", error)
                        } finally {
                            isRefreshing = false
                        }
                    }
                }
            },
            loadTrackCover = { trackId -> model.getTrackCover(trackId)?.asImageBitmap() },
            onBackClick = { navController.navigateUp() },
            onPlayClick = {
                val firstPlayableIndex = trackItems.indexOfFirst { item ->
                    !item.isPlaybackBlocked
                }
                if (firstPlayableIndex >= 0) {
                    playTrack(
                        position = firstPlayableIndex,
                        expectedSongId = trackItems[firstPlayableIndex].trackId,
                    )
                } else if (trackItems.isNotEmpty()) {
                    showSnackbar(context.getString(R.string.track_unavailable_yandex))
                }
            },
            onTrackClick = { position, item ->
                when {
                    item.isPlaybackBlocked &&
                        (item.hasMultipleSources || item.hasUnresolvedMatchCandidate) -> {
                        mergeSourcesError = null
                        sourceDialogSongId = item.trackId
                    }
                    item.isPlaybackBlocked ->
                        showSnackbar(context.getString(R.string.track_unavailable_yandex))
                    else -> playTrack(position, item.trackId)
                }
            },
            onShareClick = {
                Log.d(TAG, "[shareObject] Поделиться объектом пока не подключено")
            },
            onWaveClick = {
                model.requestWave()
                navController.navigate(DwijDestination.PLAYER)
            },
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
        )
    }

    if (sourceDialogSong != null) {
        MultiSourceDialog(
            options = sourceOptions,
            loadCover = { instanceId ->
                sourceInstancesById[instanceId]
                    ?.let { instance -> playerModel.cover(instance) }
                    ?.firstOrNull()
                    ?.asImageBitmap()
            },
            onDismiss = { sourceDialogSongId = null },
            onSave = onSave@{ selectedIds ->
                val selectedEntries = sourceEntries.filter { entry ->
                    entry.instance.id in selectedIds
                }
                if (selectedEntries.size < 2 || isMergingSources) return@onSave
                coroutineScope.launch {
                    isMergingSources = true
                    mergeSourcesError = null
                    try {
                        val sourceSongIds = selectedEntries
                            .mapTo(linkedSetOf(), ObjectSourceDialogEntry::songId)
                        val (mergedSongId, mergedSong) = withContext(Dispatchers.IO) {
                            val resultId = songRepository.mergeInstances(
                                selectedEntries.map(ObjectSourceDialogEntry::instance),
                            )
                            val resultSong = requireNotNull(
                                songRepository.songsByIds(listOf(resultId)).firstOrNull(),
                            ) { "Объединённая Song $resultId не найдена" }
                            resultId to resultSong
                        }
                        model.applyMergedSong(sourceSongIds, mergedSong)
                        playerModel.applyMergedSong(sourceSongIds, mergedSong)
                        sourceDialogSongId = null
                        showSnackbar(
                            context.getString(R.string.multi_source_merge_success, mergedSongId),
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.e(TAG, "[mergeSources] Не удалось объединить источники", error)
                        mergeSourcesError = context.getString(
                            R.string.multi_source_merge_error,
                            error.message ?: error.javaClass.simpleName,
                        )
                    } finally {
                        isMergingSources = false
                    }
                }
            },
            isSaving = isMergingSources,
            errorMessage = mergeSourcesError,
        )
    }
}

/** Создаёт уникальные ключи для повторяющихся Song внутри одного объекта. */
private fun List<Song>.toObjectTrackListItems(
    unknownArtist: String,
    cachedUnavailableSongIds: Set<String>,
): List<TrackListItemUiModel> {
    val occurrences = mutableMapOf<String, Int>()
    return map { song ->
        val occurrence = occurrences.getOrDefault(song.id, 0)
        occurrences[song.id] = occurrence + 1
        val yandexUnavailable = song.yandexInstances.isNotEmpty() &&
            song.yandexInstances.none { instance -> instance.track.available }
        val yandexPlayable = song.yandexInstances.any { instance ->
            instance.track.available || song.id in cachedUnavailableSongIds
        }
        TrackListItemUiModel(
            key = "${song.id}:$occurrence",
            trackId = song.id,
            title = song.title,
            artist = song.artistNames.joinToString(", ").ifBlank { unknownArtist },
            shouldLoadCover = song.coverUri != null,
            isYandexUnavailable = yandexUnavailable,
            isPlaybackBlocked = song.localInstances.isEmpty() && !yandexPlayable,
            hasMultipleSources = song.instances.size > 1,
            hasUnresolvedMatchCandidate = song.hasPendingMatchCandidate,
        )
    }
}

/** Связывает вариант диалога с Song, к которой он относился до merge. */
private data class ObjectSourceDialogEntry(
    val song: Song,
    val instance: TrackInstance,
) {
    val songId: String
        get() = song.id
}

private const val TAG = "ObjectRoute"
