package com.yellastrodev.dwij.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yellastrodev.dwij.TrackListItemUiModel
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.SongMatchCandidateEntity
import com.yellastrodev.dwij.data.entities.TrackInstance
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.models.CatalogObjectKind
import com.yellastrodev.dwij.models.CatalogObjectModel
import com.yellastrodev.dwij.models.CatalogObjectUiState
import com.yellastrodev.dwij.models.TracklistModel
import com.yellastrodev.dwij.resources.Res
import com.yellastrodev.dwij.resources.home_player_unknown_artist
import com.yellastrodev.dwij.resources.ic_share
import com.yellastrodev.dwij.resources.multi_source_merge_error
import com.yellastrodev.dwij.resources.multi_source_merge_success
import com.yellastrodev.dwij.resources.multi_source_priority_error
import com.yellastrodev.dwij.resources.object_loading_title
import com.yellastrodev.dwij.resources.object_share
import com.yellastrodev.dwij.resources.object_track_count
import com.yellastrodev.dwij.resources.track_list_all_title
import com.yellastrodev.dwij.resources.track_list_empty
import com.yellastrodev.dwij.resources.catalog_artist_popular_empty
import com.yellastrodev.dwij.resources.catalog_likes_count
import com.yellastrodev.dwij.resources.catalog_load_error
import com.yellastrodev.dwij.resources.catalog_monthly_listeners
import com.yellastrodev.dwij.resources.catalog_yandex_track_count
import com.yellastrodev.dwij.resources.track_unavailable_yandex
import com.yellastrodev.dwij.resources.track_save_locally
import com.yellastrodev.dwij.resources.track_saved_locally
import com.yellastrodev.dwij.resources.track_saving_locally
import com.yellastrodev.dwij.resources.track_save_locally_started
import com.yellastrodev.dwij.ui.LocalYamLogger
import com.yellastrodev.dwij.ui.MultiSourceDialog
import com.yellastrodev.dwij.ui.ObjectScreen
import com.yellastrodev.dwij.ui.TrackSourceIndicator
import com.yellastrodev.dwij.ui.TrackSourceOptionUiModel
import com.yellastrodev.dwij.ui.toImageBitmapOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** Shared-route абстрактного музыкального объекта и его списка треков. */
@Composable
fun ObjectRoute(
    component: DwijComponent,
    playerModel: PlayerModel,
    objectType: String,
    objectValue: String,
    onBackClick: () -> Unit,
    onOpenPlayer: () -> Unit,
    onRequestLocalTrackDownload: (trackId: String, title: String) -> Unit,
    onShareYandexUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val catalogKind = when (objectType) {
        DwijDestination.OBJECT_TYPE_ARTIST -> CatalogObjectKind.Artist
        DwijDestination.OBJECT_TYPE_ALBUM -> CatalogObjectKind.Album
        else -> null
    }
    if (catalogKind != null) {
        CatalogObjectRoute(
            component = component,
            kind = catalogKind,
            externalId = objectValue.toIntOrNull(),
            onBackClick = onBackClick,
            onOpenPlayer = onOpenPlayer,
            onShareYandexUrl = onShareYandexUrl,
            modifier = modifier,
        )
        return
    }

    val logger = LocalYamLogger.current
    val songMatchRepository = component.songMatchRepository
    val songRepository = component.songRepository

    val modelFactory = remember(component, logger) {
        TracklistModel.Factory(
            repo = component.playlistRepository,
            coverRepo = component.coverRepository,
            trackRepo = component.trackRepository,
            trackCacheRepo = component.trackCacheRepo,
            songRepo = component.songRepository,
            playerRepo = component.playerRepo,
            waveRepo = component.waveRepository,
            logger = logger,
        )
    }

    val model = viewModel<TracklistModel>(
        key = "object:$objectType:$objectValue",
        factory = modelFactory,
    )

    val playlist by model.playlist.collectAsState()
    val tracks by model.tracks.collectAsState()
    val isLoading by model.isLoading.collectAsState()
    val totalTrackCount by model.totalTrackCount.collectAsState()
    val cachedUnavailableSongIds by model.cachedUnavailableSongIds.collectAsState()
    val localStorageRevision by
        component.trackCacheRepo.localStorageRevision.collectAsState()
    val localDownloads by component.trackCacheRepo.localDownloads.collectAsState()

    var savedYandexTrackIds by remember {
        mutableStateOf(emptySet<String>())
    }

    LaunchedEffect(tracks, localStorageRevision) {
        val yandexTrackIds = tracks
            .mapNotNull(Song::localSaveYandexTrackId)
            .distinct()
        savedYandexTrackIds = withContext(Dispatchers.IO) {
            yandexTrackIds
                .filter(component.trackCacheRepo::isSavedLocally)
                .toSet()
        }
    }

    val yandexPlaylist = playlist as? dYaPlaylist
    val objectId = yandexPlaylist?.playlistUuid
    val unknownArtist = stringResource(Res.string.home_player_unknown_artist)
    val unavailableMessage = stringResource(Res.string.track_unavailable_yandex)
    val saveLocallyStartedMessage =
        stringResource(Res.string.track_save_locally_started)

    val title = when {
        objectType == DwijDestination.OBJECT_TYPE_TRACKLIST ->
            stringResource(Res.string.track_list_all_title)

        yandexPlaylist != null -> yandexPlaylist.title
        else -> stringResource(Res.string.object_loading_title)
    }

    val count = when {
        yandexPlaylist != null -> yandexPlaylist.trackCount
        objectType == DwijDestination.OBJECT_TYPE_TRACKLIST -> totalTrackCount ?: 0
        else -> tracks.size
    }
    val subtitle = pluralStringResource(
        Res.plurals.object_track_count,
        count,
        count,
    )

    val trackItems = remember(
        tracks,
        cachedUnavailableSongIds,
        savedYandexTrackIds,
        localDownloads,
        unknownArtist,
    ) {
        tracks.toObjectTrackListItems(
            unknownArtist = unknownArtist,
            cachedUnavailableSongIds = cachedUnavailableSongIds,
            savedYandexTrackIds = savedYandexTrackIds,
            savingYandexTrackIds = localDownloads.keys,
        )
    }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var objectCover by remember(objectId) {
        mutableStateOf<ImageBitmap?>(null)
    }
    var isRefreshing by remember { mutableStateOf(false) }
    var sourceDialogSongId by remember { mutableStateOf<String?>(null) }
    var candidateSongs by remember(sourceDialogSongId) {
        mutableStateOf(emptyList<Song>())
    }
    var isMergingSources by remember(sourceDialogSongId) {
        mutableStateOf(false)
    }
    var isUpdatingSourcePriority by remember(sourceDialogSongId) {
        mutableStateOf(false)
    }
    var mergeSourcesError by remember(sourceDialogSongId) {
        mutableStateOf<String?>(null)
    }
    var cachedUnavailableYandexIds by remember(sourceDialogSongId) {
        mutableStateOf(emptySet<String>())
    }

    val sourceDialogSong = remember(tracks, sourceDialogSongId) {
        tracks.firstOrNull { song ->
            song.id == sourceDialogSongId
        }
    }

    val pendingMatchCandidates by remember(sourceDialogSongId) {
        sourceDialogSongId
            ?.let(songMatchRepository::pendingCandidatesForSong)
            ?: flowOf(emptyList<SongMatchCandidateEntity>())
    }.collectAsState(initial = emptyList())

    fun showSnackbar(message: String) {
        coroutineScope.launch {
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(objectType, objectValue) {
        model.setType(
            type = objectType,
            value = objectValue
                .takeUnless { it == "_" }
                .orEmpty(),
        )
    }

    LaunchedEffect(sourceDialogSongId, pendingMatchCandidates) {
        val currentSongId = sourceDialogSongId
            ?: return@LaunchedEffect

        val relatedSongIds = pendingMatchCandidates
            .flatMap { candidate ->
                listOf(
                    candidate.firstSongId,
                    candidate.secondSongId,
                )
            }
            .filterNot { songId -> songId == currentSongId }
            .distinct()

        candidateSongs = try {
            withContext(Dispatchers.IO) {
                songRepository.songsByIds(relatedSongIds)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.error(
                TAG,
                "[loadMultiSourceCandidates] Не удалось загрузить варианты " +
                    "songId=$currentSongId",
                error,
            )
            emptyList()
        }
    }

    val confirmedSourceEntries = remember(sourceDialogSong) {
        sourceDialogSong?.instances.orEmpty().map { instance ->
            ObjectSourceDialogEntry(
                song = requireNotNull(sourceDialogSong),
                instance = instance,
            )
        }
    }

    val candidateSourceEntries = remember(candidateSongs) {
        candidateSongs.flatMap { song ->
            song.instances.map { instance ->
                ObjectSourceDialogEntry(
                    song = song,
                    instance = instance,
                )
            }
        }.distinctBy { entry -> entry.instance.id }
    }

    val sourceEntries = remember(confirmedSourceEntries, candidateSourceEntries) {
        (confirmedSourceEntries + candidateSourceEntries)
            .distinctBy { entry -> entry.instance.id }
    }

    LaunchedEffect(sourceDialogSongId, sourceEntries) {
        if (sourceDialogSongId == null) return@LaunchedEffect
        val unavailableIds = sourceEntries.mapNotNull { entry ->
            (entry.instance as? TrackInstance.Yandex)
                ?.track
                ?.takeIf { yandexTrack -> !yandexTrack.available }
                ?.id
        }.distinct()
        cachedUnavailableYandexIds = withContext(Dispatchers.IO) {
            unavailableIds
                .filter(component.trackCacheRepo::isCached)
                .toSet()
        }
    }

    fun ObjectSourceDialogEntry.toOption(): TrackSourceOptionUiModel =
        TrackSourceOptionUiModel(
            instanceId = instance.id,
            item = TrackListItemUiModel(
                key = instance.id,
                trackId = song.id,
                title = song.title,
                artist = song.artistNames
                    .joinToString(", ")
                    .ifBlank { unknownArtist },
                isYandexUnavailable =
                    (instance as? TrackInstance.Yandex)
                        ?.track
                        ?.available == false,
            ),
            sourceIndicator = when (instance) {
                is TrackInstance.Yandex -> TrackSourceIndicator.YANDEX
                is TrackInstance.Local -> TrackSourceIndicator.LOCAL
            },
            isPlayable = instance.isPlayable(cachedUnavailableYandexIds),
        )

    val confirmedSourceOptions = remember(
        confirmedSourceEntries,
        unknownArtist,
        cachedUnavailableYandexIds,
    ) {
        confirmedSourceEntries.map { entry -> entry.toOption() }
    }

    val candidateSourceOptions = remember(
        candidateSourceEntries,
        unknownArtist,
        cachedUnavailableYandexIds,
    ) {
        candidateSourceEntries.map { entry -> entry.toOption() }
    }

    val effectivePreferredInstanceId = remember(
        sourceDialogSong,
        cachedUnavailableYandexIds,
    ) {
        sourceDialogSong?.preferredInstanceId
            ?.takeIf { preferredId ->
                sourceDialogSong?.instances?.any { instance ->
                    instance.id == preferredId &&
                        instance.isPlayable(cachedUnavailableYandexIds)
                } == true
            }
            ?: sourceDialogSong?.localInstances?.firstOrNull()?.id
            ?: sourceDialogSong?.yandexInstances?.firstOrNull { instance ->
                instance.isPlayable(cachedUnavailableYandexIds)
            }?.id
    }

    val sourceInstancesById = remember(sourceEntries) {
        sourceEntries.associate { entry ->
            entry.instance.id to entry.instance
        }
    }

    LaunchedEffect(objectId) {
        objectCover = null

        val currentPlaylist = yandexPlaylist
            ?: return@LaunchedEffect

        if (currentPlaylist.ogImageUri.isNullOrBlank()) {
            return@LaunchedEffect
        }

        objectCover = try {
            model
                .getPlaylistCover(currentPlaylist)
                .toImageBitmapOrNull()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.error(
                TAG,
                "[loadObjectCover] Не удалось загрузить обложку " +
                    "objectId=$objectId",
                error,
            )
            null
        }
    }

    LaunchedEffect(listState) {
        model.scrollResetEvents.collect {
            listState.scrollToItem(0)
        }
    }

    fun playTrack(
        position: Int,
        expectedSongId: String? = null,
    ) {
        if (model.onTrackClicked(position, expectedSongId)) {
            onOpenPlayer()
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        ObjectScreen(
            title = title,
            subtitle = subtitle,
            description = yandexPlaylist?.description,
            cover = objectCover,
            tracks = trackItems,
            listState = listState,
            showShare =
                objectType == DwijDestination.OBJECT_TYPE_PLAYLIST &&
                    yandexPlaylist != null,
            showWave = objectType == DwijDestination.OBJECT_TYPE_PLAYLIST,
            emptyMessage = stringResource(Res.string.track_list_empty),
            isLoading = isLoading,
            isRefreshing = isRefreshing,
            onRefresh = {
                if (!isRefreshing) {
                    coroutineScope.launch {
                        isRefreshing = true

                        try {
                            val result = model.refreshObject()
                            if (
                                result is DataResult.Failure &&
                                result.error == DataError.Unauthorized
                            ) {
                                component.requireYandexAuthorization()
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            logger.error(
                                TAG,
                                "[refreshObject] Не удалось обновить объект",
                                error,
                            )
                        } finally {
                            isRefreshing = false
                        }
                    }
                }
            },
            loadTrackCover = { trackId ->
                model
                    .getTrackCover(trackId)
                    ?.toImageBitmapOrNull()
            },
            onBackClick = onBackClick,
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
                    showSnackbar(unavailableMessage)
                }
            },
            onTrackClick = { position, item ->
                when {
                    item.isPlaybackBlocked &&
                        (item.hasMultipleSources || item.hasUnresolvedMatchCandidate) -> {
                        mergeSourcesError = null
                        sourceDialogSongId = item.trackId
                    }

                    item.isPlaybackBlocked -> {
                        showSnackbar(unavailableMessage)
                    }

                    else -> {
                        playTrack(
                            position = position,
                            expectedSongId = item.trackId,
                        )
                    }
                }
            },
            trackContextMenuContent = { _, item, onDismiss ->
                item.yandexTrackId?.let { yandexTrackId ->
                    YandexShareMenuItem(
                        onClick = {
                            onDismiss()
                            onShareYandexUrl(YandexMusicShareLinks.track(yandexTrackId))
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    when {
                                        item.isSavedLocally -> Res.string.track_saved_locally
                                        item.isSavingLocally -> Res.string.track_saving_locally
                                        else -> Res.string.track_save_locally
                                    },
                                ),
                            )
                        },
                        enabled = !item.isSavedLocally && !item.isSavingLocally,
                        onClick = {
                            onDismiss()
                            onRequestLocalTrackDownload(yandexTrackId, item.title)
                            showSnackbar(saveLocallyStartedMessage)
                        },
                    )
                }
            },
            onShareClick = {
                yandexPlaylist?.playlistUuid?.let { playlistUuid ->
                    onShareYandexUrl(YandexMusicShareLinks.playlist(playlistUuid))
                }
            },
            onWaveClick = {
                model.requestWave()
                onOpenPlayer()
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
        val manageConfirmedSources = confirmedSourceOptions.size > 1
        MultiSourceDialog(
            confirmedOptions = if (manageConfirmedSources) {
                confirmedSourceOptions
            } else {
                emptyList()
            },
            candidateOptions = if (manageConfirmedSources) {
                candidateSourceOptions
            } else {
                confirmedSourceOptions + candidateSourceOptions
            },
            preferredInstanceId = effectivePreferredInstanceId,
            loadCover = { instanceId ->
                sourceInstancesById[instanceId]
                    ?.let { instance ->
                        playerModel.cover(instance)
                    }
                    ?.firstOrNull()
            },
            onDismiss = {
                sourceDialogSongId = null
            },
            onPreferredInstanceChange = onPreferredInstanceChange@{ instanceId ->
                val currentSong = sourceDialogSong ?: return@onPreferredInstanceChange
                if (isMergingSources || isUpdatingSourcePriority) {
                    return@onPreferredInstanceChange
                }
                coroutineScope.launch {
                    isUpdatingSourcePriority = true
                    mergeSourcesError = null
                    try {
                        val updatedSong = withContext(Dispatchers.IO) {
                            songRepository.setPreferredInstance(currentSong.id, instanceId)
                            requireNotNull(
                                songRepository
                                    .songsByIds(listOf(currentSong.id))
                                    .firstOrNull(),
                            ) { "Song ${currentSong.id} не найдена после смены приоритета" }
                        }
                        model.applyMergedSong(
                            sourceSongIds = setOf(currentSong.id),
                            mergedSong = updatedSong,
                        )
                        playerModel.applyMergedSong(
                            sourceSongIds = setOf(currentSong.id),
                            mergedSong = updatedSong,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        logger.error(
                            TAG,
                            "[updateSourcePriority] Не удалось сохранить приоритет источника",
                            error,
                        )
                        mergeSourcesError = getString(
                            Res.string.multi_source_priority_error,
                            error.message ?: error.toString(),
                        )
                    } finally {
                        isUpdatingSourcePriority = false
                    }
                }
            },
            onSaveCandidates = onSaveCandidates@{ selectedIds ->
                val selectedEntries = candidateSourceEntries.filter { entry ->
                    entry.instance.id in selectedIds
                }

                val anchorEntry = confirmedSourceEntries.firstOrNull()
                    ?: return@onSaveCandidates
                if (
                    selectedEntries.isEmpty() ||
                    isMergingSources ||
                    isUpdatingSourcePriority
                ) {
                    return@onSaveCandidates
                }
                val entriesToMerge = listOf(anchorEntry) + selectedEntries

                coroutineScope.launch {
                    isMergingSources = true
                    mergeSourcesError = null

                    try {
                        val sourceSongIds = entriesToMerge
                            .mapTo(
                                linkedSetOf(),
                                ObjectSourceDialogEntry::songId,
                            )

                        val (mergedSongId, mergedSong) =
                            withContext(Dispatchers.IO) {
                                val resultId = songRepository.mergeInstances(
                                    entriesToMerge.map(
                                        ObjectSourceDialogEntry::instance,
                                    ),
                                )

                                val resultSong = requireNotNull(
                                    songRepository
                                        .songsByIds(listOf(resultId))
                                        .firstOrNull(),
                                ) {
                                    "Объединённая Song $resultId не найдена"
                                }

                                resultId to resultSong
                            }

                        model.applyMergedSong(
                            sourceSongIds = sourceSongIds,
                            mergedSong = mergedSong,
                        )

                        playerModel.applyMergedSong(
                            sourceSongIds = sourceSongIds,
                            mergedSong = mergedSong,
                        )

                        sourceDialogSongId = null

                        showSnackbar(
                            getString(
                                Res.string.multi_source_merge_success,
                                mergedSongId,
                            ),
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        logger.error(
                            TAG,
                            "[mergeSources] Не удалось объединить источники",
                            error,
                        )

                        mergeSourcesError = getString(
                            Res.string.multi_source_merge_error,
                            error.message ?: error.toString(),
                        )
                    } finally {
                        isMergingSources = false
                    }
                }
            },
            manageConfirmedSources = manageConfirmedSources,
            minimumCandidateSelectionCount = if (manageConfirmedSources) 1 else 2,
            isSaving = isMergingSources || isUpdatingSourcePriority,
            errorMessage = mergeSourcesError,
        )
    }
}

/** Экран альбома или артиста ЯМ поверх общих canonical Song и ObjectScreen. */
@Composable
private fun CatalogObjectRoute(
    component: DwijComponent,
    kind: CatalogObjectKind,
    externalId: Int?,
    onBackClick: () -> Unit,
    onOpenPlayer: () -> Unit,
    onShareYandexUrl: (String) -> Unit,
    modifier: Modifier,
) {
    val factory = remember(component) {
        CatalogObjectModel.Factory(
            catalogRepository = component.catalogRepository,
            coverRepository = component.coverRepository,
            playerRepository = component.playerRepo,
        )
    }
    val model = viewModel<CatalogObjectModel>(
        key = "catalog:${kind.name}:$externalId",
        factory = factory,
    )
    val state by model.state.collectAsState()
    val unknownArtist = stringResource(Res.string.home_player_unknown_artist)
    var cover by remember(state.externalId, state.coverUri) {
        mutableStateOf<ImageBitmap?>(null)
    }

    LaunchedEffect(kind, externalId) {
        if (externalId != null) {
            model.load(kind, externalId)
        }
    }
    LaunchedEffect(state.coverUri) {
        cover = model.objectCover()?.toImageBitmapOrNull()
    }
    LaunchedEffect(state.error) {
        if (state.error == DataError.Unauthorized) {
            component.requireYandexAuthorization()
        }
    }

    val trackItems = remember(state.tracks, unknownArtist) {
        state.tracks.toObjectTrackListItems(
            unknownArtist = unknownArtist,
            cachedUnavailableSongIds = emptySet(),
            savedYandexTrackIds = emptySet(),
            savingYandexTrackIds = emptySet(),
        )
    }
    val subtitle = when (kind) {
        CatalogObjectKind.Artist -> buildList {
            state.lastMonthListeners?.let { listeners ->
                add(
                    stringResource(
                        Res.string.catalog_monthly_listeners,
                        listeners.toCompactMetric(),
                    ),
                )
            }
            state.trackCount?.let { trackCount ->
                add(
                    pluralStringResource(
                        Res.plurals.catalog_yandex_track_count,
                        trackCount,
                        trackCount,
                    ),
                )
            }
        }.joinToString(" · ")

        CatalogObjectKind.Album -> buildList {
            state.artistNames.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let(::add)
            state.albumYear()?.let(::add)
            state.likesCount?.let { likes ->
                add(stringResource(Res.string.catalog_likes_count, likes))
            }
        }.joinToString(" · ")
    }
    val emptyMessage = when {
        state.error != null -> stringResource(Res.string.catalog_load_error)
        kind == CatalogObjectKind.Artist ->
            stringResource(Res.string.catalog_artist_popular_empty)
        else -> stringResource(Res.string.track_list_empty)
    }

    ObjectScreen(
        title = state.title.ifBlank {
            stringResource(Res.string.object_loading_title)
        },
        subtitle = subtitle,
        description = state.description,
        cover = cover,
        tracks = trackItems,
        onBackClick = onBackClick,
        onPlayClick = {
            val firstPlayable = trackItems.indexOfFirst { !it.isPlaybackBlocked }
            if (firstPlayable >= 0 && model.play(firstPlayable)) {
                onOpenPlayer()
            }
        },
        onTrackClick = { index, item ->
            if (!item.isPlaybackBlocked && model.play(index)) {
                onOpenPlayer()
            }
        },
        trackContextMenuContent = { _, item, onDismiss ->
            item.yandexTrackId?.let { trackId ->
                YandexShareMenuItem(
                    onClick = {
                        onDismiss()
                        onShareYandexUrl(YandexMusicShareLinks.track(trackId))
                    },
                )
            }
        },
        loadTrackCover = { songId ->
            model.trackCover(songId)?.toImageBitmapOrNull()
        },
        showShare = externalId != null,
        onShareClick = {
            externalId?.let { id ->
                val url = when (kind) {
                    CatalogObjectKind.Artist -> YandexMusicShareLinks.artist(id)
                    CatalogObjectKind.Album -> YandexMusicShareLinks.album(id)
                }
                onShareYandexUrl(url)
            }
        },
        showWave = kind == CatalogObjectKind.Artist && externalId != null,
        onWaveClick = {
            val artistId = externalId
            if (
                kind == CatalogObjectKind.Artist &&
                artistId != null &&
                component.waveRepository.requestArtistWave(
                    artistId = artistId,
                    artistName = state.title,
                )
            ) {
                onOpenPlayer()
            }
        },
        emptyMessage = emptyMessage,
        isLoading = state.isLoading && state.tracks.isEmpty(),
        isRefreshing = state.isLoading && state.tracks.isNotEmpty(),
        onRefresh = model::refresh,
        modifier = modifier,
    )
}

/** Единый пункт отправки ссылки на ЯМ-трек в контекстных меню списков. */
@Composable
private fun YandexShareMenuItem(onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.object_share)) },
        leadingIcon = {
            Image(
                painter = painterResource(Res.drawable.ic_share),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
        },
        onClick = onClick,
    )
}

/** Для экрана альбома оставляет от полной даты только год. */
private fun CatalogObjectUiState.albumYear(): String? =
    releaseDate
        ?.trim()
        ?.take(4)
        ?.takeIf { value -> value.length == 4 && value.all(Char::isDigit) }
        ?: year?.toString()

/** Компактно показывает счётчик максимум тремя значащими цифрами. */
private fun Long.toCompactMetric(): String {
    val (scaled, suffix) = when {
        this >= 1_000_000L -> this / 1_000_000.0 to "М"
        this >= 1_000L -> this / 1_000.0 to "К"
        else -> return toString()
    }
    val decimals = when {
        scaled >= 100.0 -> 0
        scaled >= 10.0 -> 1
        else -> 2
    }
    return java.lang.String
        .format(java.util.Locale.ROOT, "%.${decimals}f", scaled)
        .trimEnd('0')
        .trimEnd('.')
        .replace('.', ',') + suffix
}

/** Создаёт уникальные ключи для повторяющихся Song внутри одного объекта. */
private fun List<Song>.toObjectTrackListItems(
    unknownArtist: String,
    cachedUnavailableSongIds: Set<String>,
    savedYandexTrackIds: Set<String>,
    savingYandexTrackIds: Set<String>,
): List<TrackListItemUiModel> {
    val occurrences = mutableMapOf<String, Int>()

    return map { song ->
        val occurrence = occurrences.getOrDefault(song.id, 0)
        occurrences[song.id] = occurrence + 1

        val yandexUnavailable =
            song.yandexInstances.isNotEmpty() &&
                song.yandexInstances.none { instance ->
                    instance.track.available
                }

        val yandexPlayable = song.yandexInstances.any { instance ->
            instance.track.available ||
                song.id in cachedUnavailableSongIds
        }
        val localSaveYandexTrackId = song.localSaveYandexTrackId()

        TrackListItemUiModel(
            key = "${song.id}:$occurrence",
            trackId = song.id,
            title = song.title,
            artist = song.artistNames
                .joinToString(", ")
                .ifBlank { unknownArtist },
            shouldLoadCover = song.coverUri != null,
            yandexTrackId = localSaveYandexTrackId,
            isYandexUnavailable = yandexUnavailable,
            isPlaybackBlocked =
                song.localInstances.isEmpty() && !yandexPlayable,
            hasMultipleSources = song.instances.size > 1,
            hasUnresolvedMatchCandidate = song.hasPendingMatchCandidate,
            isSavedLocally = localSaveYandexTrackId != null &&
                localSaveYandexTrackId in savedYandexTrackIds,
            isSavingLocally = localSaveYandexTrackId != null &&
                localSaveYandexTrackId in savingYandexTrackIds,
        )
    }
}

/** Выбирает ЯМ-инстанс строки: сначала preferred, затем первый доступный. */
private fun Song.localSaveYandexTrackId(): String? =
    instances
        .firstOrNull { instance -> instance.id == preferredInstanceId }
        ?.let { instance -> instance as? TrackInstance.Yandex }
        ?.track
        ?.id
        ?: yandexInstances.firstOrNull()?.track?.id

/** Связывает вариант диалога с Song, к которой он относился до merge. */
private data class ObjectSourceDialogEntry(
    val song: Song,
    val instance: TrackInstance,
) {
    val songId: String
        get() = song.id
}

/** Проверяет playable-состояние конкретной версии, а не всей агрегированной Song. */
private fun TrackInstance.isPlayable(cachedUnavailableYandexIds: Set<String>): Boolean =
    when (this) {
        is TrackInstance.Local -> true
        is TrackInstance.Yandex -> track.available || track.id in cachedUnavailableYandexIds
    }

private const val TAG = "ObjectRoute"
