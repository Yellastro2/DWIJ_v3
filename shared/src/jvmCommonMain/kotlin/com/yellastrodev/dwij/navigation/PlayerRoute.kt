package com.yellastrodev.dwij.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.yellastrodev.dwij.TrackListItemUiModel
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.MusicSource
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.SongMatchCandidateEntity
import com.yellastrodev.dwij.data.entities.TrackInstance
import com.yellastrodev.dwij.data.entities.dYaLikeTracklist.Companion.KIND_LIKED
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.resources.Res
import com.yellastrodev.dwij.resources.home_player_unknown_artist
import com.yellastrodev.dwij.resources.multi_source_merge_error
import com.yellastrodev.dwij.resources.multi_source_merge_success
import com.yellastrodev.dwij.resources.multi_source_priority_error
import com.yellastrodev.dwij.resources.player_current_queue
import com.yellastrodev.dwij.resources.player_like_failed
import com.yellastrodev.dwij.resources.player_no_track
import com.yellastrodev.dwij.resources.player_source_local
import com.yellastrodev.dwij.resources.player_source_yandex
import com.yellastrodev.dwij.resources.player_wave_loading_artist
import com.yellastrodev.dwij.resources.player_wave_loading_title
import com.yellastrodev.dwij.resources.track_save_locally_started
import com.yellastrodev.dwij.ui.FullPlayerScreen
import com.yellastrodev.dwij.ui.FullPlayerUiState
import com.yellastrodev.dwij.ui.LocalYamLogger
import com.yellastrodev.dwij.ui.MultiSourceDialog
import com.yellastrodev.dwij.ui.TrackSourceIndicator
import com.yellastrodev.dwij.ui.TrackSourceOptionUiModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** Shared-route полноэкранного плеера и выбора источников текущей песни. */
@Composable
fun PlayerRoute(
    component: DwijComponent,
    playerModel: PlayerModel,
    onBackClick: () -> Unit,
    onAddToPlaylist: (trackId: String) -> Unit,
    onRequestLocalTrackDownload: (trackId: String, title: String) -> Unit,
) {
    val logger = LocalYamLogger.current
    val songMatchRepository = component.songMatchRepository
    val songRepository = component.songRepository

    val track by playerModel.track.collectAsState()
    val playbackTrack by playerModel.playbackTrack.collectAsState()
    val playerState by playerModel.playerState.collectAsState()
    val playedTracklist by playerModel.playdTracklist.collectAsState()
    val shuffleBlocked by playerModel.shuffleBlock.collectAsState()
    val allPlaylists by playerModel.playlistRepo.playlists.collectAsState()
    val isWaveLoading by component.waveRepository.isLoading.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    val uiMessages = remember {
        MutableSharedFlow<String>(
            extraBufferCapacity = 1,
        )
    }

    var cover by remember(
        track?.id,
        playbackTrack?.instanceId,
    ) {
        mutableStateOf<ImageBitmap?>(null)
    }

    LaunchedEffect(
        track?.id,
        playbackTrack?.instanceId,
    ) {
        cover = null

        val currentTrack = track
            ?: return@LaunchedEffect

        try {
            playerModel
                .cover(currentTrack)
                .flowOn(Dispatchers.IO)
                .collect { imageBitmap ->
                    cover = imageBitmap
                }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.error(
                TAG,
                "[loadPlayerCover] Не удалось загрузить обложку " +
                    "trackId=${currentTrack.id}",
                error,
            )
        }
    }

    val currentTrackId = track?.id
    val localStorageRevision by
        component.trackCacheRepo.localStorageRevision.collectAsState()
    val localDownloads by component.trackCacheRepo.localDownloads.collectAsState()
    val currentYandexDownloadTrackId = playbackTrack
        ?.takeIf { current -> current.source == MusicSource.YANDEX }
        ?.id
    var isCurrentTrackSavedLocally by remember(currentYandexDownloadTrackId) {
        mutableStateOf(false)
    }

    LaunchedEffect(currentYandexDownloadTrackId, localStorageRevision) {
        isCurrentTrackSavedLocally = currentYandexDownloadTrackId
            ?.let { trackId ->
                withContext(Dispatchers.IO) {
                    component.trackCacheRepo.isSavedLocally(trackId)
                }
            }
            ?: false
    }

    val databaseTrack by remember(currentTrackId) {
        currentTrackId
            ?.let(songRepository::song)
            ?: flowOf(null)
    }.collectAsState(initial = track)

    val isLiked = databaseTrack
        ?.takeIf { song -> song.id == currentTrackId }
        ?.isLiked
        ?: track?.isLiked
        ?: false

    var pendingLike by remember { mutableStateOf<PendingLikeMutation?>(null) }
    val isLikePending = pendingLike?.songId == currentTrackId

    LaunchedEffect(currentTrackId) {
        if (pendingLike?.songId != currentTrackId) {
            pendingLike = null
        }
    }

    var showMultiSourceDialog by remember(currentTrackId) {
        mutableStateOf(false)
    }
    var candidateSongs by remember(currentTrackId) {
        mutableStateOf(emptyList<Song>())
    }
    var isMergingSources by remember(currentTrackId) {
        mutableStateOf(false)
    }
    var isUpdatingSourcePriority by remember(currentTrackId) {
        mutableStateOf(false)
    }
    var mergeSourcesError by remember(currentTrackId) {
        mutableStateOf<String?>(null)
    }
    var cachedUnavailableYandexIds by remember(currentTrackId) {
        mutableStateOf(emptySet<String>())
    }

    val pendingMatchCandidates by remember(currentTrackId) {
        currentTrackId
            ?.let(songMatchRepository::pendingCandidatesForSong)
            ?: flowOf(emptyList<SongMatchCandidateEntity>())
    }.collectAsState(initial = emptyList())

    LaunchedEffect(
        showMultiSourceDialog,
        currentTrackId,
        pendingMatchCandidates,
    ) {
        if (!showMultiSourceDialog || currentTrackId == null) {
            return@LaunchedEffect
        }

        val relatedSongIds = pendingMatchCandidates
            .flatMap { candidate ->
                listOf(
                    candidate.firstSongId,
                    candidate.secondSongId,
                )
            }
            .filterNot { songId -> songId == currentTrackId }
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
                    "songId=$currentTrackId",
                error,
            )
            emptyList()
        }
    }

    val yandexTrack = track
        ?.yandexInstances
        ?.firstOrNull()
        ?.track

    val yandexTrackId = yandexTrack?.id
    val playlistKeys = yandexTrack?.playlists.orEmpty()

    val containingPlaylists = remember(
        currentTrackId,
        yandexTrackId,
        playlistKeys,
        allPlaylists,
    ) {
        if (currentTrackId == null || yandexTrack == null) {
            emptyList()
        } else {
            allPlaylists.filter { playlist ->
                playlist.kind != KIND_LIKED &&
                    (
                        playlist.playlistUuid in playlistKeys ||
                            playlist.tracks.any { item ->
                                item.trackId == yandexTrackId
                            }
                    )
            }
        }
    }

    val containingPlaylistTitles = remember(containingPlaylists) {
        containingPlaylists.map { playlist -> playlist.title }
    }

    val fallbackQueueTitle = stringResource(Res.string.player_current_queue)
    val unknownArtist = stringResource(Res.string.home_player_unknown_artist)
    val waveLoadingTitle = stringResource(Res.string.player_wave_loading_title)
    val waveLoadingArtist = stringResource(Res.string.player_wave_loading_artist)
    val noTrackTitle = stringResource(Res.string.player_no_track)
    val likeFailedMessage = stringResource(Res.string.player_like_failed)

    val showWaveLoadingPlaceholder =
        isWaveLoading && currentTrackId == null

    val confirmedSourceEntries = remember(track) {
        track?.instances.orEmpty().map { instance ->
            PlayerSourceDialogEntry(
                song = requireNotNull(track),
                instance = instance,
            )
        }
    }

    val candidateSourceEntries = remember(candidateSongs) {
        candidateSongs.flatMap { song ->
            song.instances.map { instance ->
                PlayerSourceDialogEntry(
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

    LaunchedEffect(showMultiSourceDialog, sourceEntries) {
        if (!showMultiSourceDialog) return@LaunchedEffect
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

    fun PlayerSourceDialogEntry.toOption(): TrackSourceOptionUiModel =
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
        track,
        cachedUnavailableYandexIds,
    ) {
        track?.preferredInstanceId
            ?.takeIf { preferredId ->
                track?.instances?.any { instance ->
                    instance.id == preferredId &&
                        instance.isPlayable(cachedUnavailableYandexIds)
                } == true
            }
            ?: track?.localInstances?.firstOrNull()?.id
            ?: track?.yandexInstances?.firstOrNull { instance ->
                instance.isPlayable(cachedUnavailableYandexIds)
            }?.id
    }

    val sourceInstancesById = remember(sourceEntries) {
        sourceEntries.associate { entry ->
            entry.instance.id to entry.instance
        }
    }

    val sourceLabel = when (playbackTrack?.source) {
        MusicSource.YANDEX ->
            stringResource(Res.string.player_source_yandex)

        MusicSource.LOCAL ->
            stringResource(Res.string.player_source_local)

        null -> null
    }
    val saveLocallyStartedMessage =
        stringResource(Res.string.track_save_locally_started)

    FullPlayerScreen(
        state = FullPlayerUiState(
            trackId = currentTrackId,
            queueTitle = if (showWaveLoadingPlaceholder) {
                waveLoadingTitle
            } else {
                playedTracklist
                    ?.getDTitle()
                    ?.takeIf(String::isNotBlank)
                    ?: fallbackQueueTitle
            },
            queuePosition =
                (playerState.currentIndex + 1).coerceAtLeast(1),
            title = if (showWaveLoadingPlaceholder) {
                waveLoadingTitle
            } else {
                track?.title ?: noTrackTitle
            },
            artist = if (showWaveLoadingPlaceholder) {
                waveLoadingArtist
            } else {
                track
                    ?.artistNames
                    ?.joinToString(", ")
                    ?.takeIf(String::isNotBlank)
                    ?: unknownArtist
            },
            album = track
                ?.albumTitle
                ?.takeIf(String::isNotBlank),
            sourceLabel = sourceLabel,
            hasMultipleSources =
                (track?.instances?.size ?: 0) > 1,
            hasUnresolvedMatchCandidate =
                pendingMatchCandidates.isNotEmpty(),
            canSaveLocally = currentYandexDownloadTrackId != null,
            isSavedLocally = isCurrentTrackSavedLocally,
            isSavingLocally = currentYandexDownloadTrackId != null &&
                currentYandexDownloadTrackId in localDownloads,
            cover = cover,
            isPlaying = playerState.wantsToPlay,
            currentPositionMillis = playerState.currentPosition,
            durationMillis = playerState.duration,
            isShuffle = playerState.isShuffle,
            isRepeatAll = playerState.isRepeatAll,
            showPlaybackModes = !shuffleBlocked,
            canStartTrackWave = yandexTrack != null,
            canLike = yandexTrack != null,
            isLiked = isLiked,
            isLikePending = isLikePending,
            playlistTitles = containingPlaylistTitles,
            isWaveLoading = showWaveLoadingPlaceholder,
            pendingTrackChange = playerState.pendingTrackChange,
        ),
        playerEvents = playerModel.playerEvent,
        uiMessages = uiMessages,
        onBackClick = onBackClick,
        onPlayPauseClick = playerModel::playAudio,
        onPreviousClick = {
            coroutineScope.launch {
                playerModel.prevTrack()
            }
        },
        onNextClick = {
            coroutineScope.launch {
                playerModel.nextTrack()
            }
        },
        onSeek = playerModel::seekTo,
        onShuffleClick = playerModel::shuffle,
        onRepeatClick = playerModel::rotate,
        onTrackWaveClick = {
            yandexTrack?.let { currentYandexTrack ->
                component.waveRepository.requestTrackWave(
                    trackId = currentYandexTrack.id,
                    trackTitle = currentYandexTrack.title,
                )
            }
        },
        onLikeClick = {
            val requestSongId = currentTrackId
            val requestTrackId = yandexTrackId
            if (requestSongId != null && requestTrackId != null && pendingLike == null) {
                val request = PendingLikeMutation(
                    songId = requestSongId,
                    trackId = requestTrackId,
                    liked = !isLiked,
                )
                pendingLike = request
                coroutineScope.launch {
                    try {
                        when (val result = playerModel.likeTrack(
                            trackId = request.trackId,
                            liked = request.liked,
                        )) {
                            is DataResult.Success -> {
                                val confirmedSong = withTimeoutOrNull(
                                    ROOM_LIKE_OBSERVATION_TIMEOUT_MS,
                                ) {
                                    songRepository
                                        .song(request.songId)
                                        .filterNotNull()
                                        .first { song -> song.isLiked == request.liked }
                                }
                                if (confirmedSong != null) {
                                    playerModel.applyUpdatedSong(confirmedSong)
                                    logger.debug(
                                        TAG,
                                        "[likeTrack] Room и route подтвердили: " +
                                            "trackId=${request.trackId}, liked=${request.liked}",
                                    )
                                } else {
                                    logger.error(
                                        TAG,
                                        "[likeTrack] Room обновлён, но route не получил статус: " +
                                            "trackId=${request.trackId}, liked=${request.liked}",
                                    )
                                    uiMessages.emit(likeFailedMessage)
                                }
                            }

                            is DataResult.Failure -> {
                                if (result.error == DataError.Unauthorized) {
                                    component.requireYandexAuthorization()
                                }
                                logger.error(
                                    TAG,
                                    "[likeTrack] Лайк не изменён: ${result.error}",
                                )
                                if (result.error != DataError.Unauthorized) {
                                    uiMessages.emit(likeFailedMessage)
                                }
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        logger.error(
                            TAG,
                            "[likeTrack] Не удалось изменить лайк",
                            error,
                        )
                        uiMessages.emit(likeFailedMessage)
                    } finally {
                        if (pendingLike == request) {
                            pendingLike = null
                        }
                    }
                }
            }
        },
        onAddToPlaylistClick = {
            yandexTrackId?.let(onAddToPlaylist)
        },
        onSourcesClick = {
            mergeSourcesError = null
            showMultiSourceDialog = true
        },
        onSaveLocallyClick = {
            val trackId = currentYandexDownloadTrackId
            if (trackId != null && !isCurrentTrackSavedLocally) {
                onRequestLocalTrackDownload(
                    trackId,
                    track?.title ?: playbackTrack?.title ?: trackId,
                )
                uiMessages.tryEmit(saveLocallyStartedMessage)
            }
        },
    )

    if (showMultiSourceDialog) {
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
                showMultiSourceDialog = false
            },
            onPreferredInstanceChange = onPreferredInstanceChange@{ instanceId ->
                val currentSong = track ?: return@onPreferredInstanceChange
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
                                PlayerSourceDialogEntry::songId,
                            )

                        val (mergedSongId, mergedSong) =
                            withContext(Dispatchers.IO) {
                                val resultId = songRepository.mergeInstances(
                                    entriesToMerge.map(
                                        PlayerSourceDialogEntry::instance,
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

                        playerModel.applyMergedSong(
                            sourceSongIds = sourceSongIds,
                            mergedSong = mergedSong,
                        )

                        showMultiSourceDialog = false

                        uiMessages.emit(
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

/** Связывает отображаемый source-инстанс с исходной Song до объединения. */
private data class PlayerSourceDialogEntry(
    val song: Song,
    val instance: TrackInstance,
) {
    val songId: String
        get() = song.id
}

/** Локальный файл всегда playable; Яндекс-трек — только при доступности или наличии в кэше. */
private fun TrackInstance.isPlayable(cachedUnavailableYandexIds: Set<String>): Boolean =
    when (this) {
        is TrackInstance.Local -> true
        is TrackInstance.Yandex -> track.available || track.id in cachedUnavailableYandexIds
    }

private const val TAG = "PlayerRoute"

private const val ROOM_LIKE_OBSERVATION_TIMEOUT_MS = 2_000L

private data class PendingLikeMutation(
    val songId: String,
    val trackId: String,
    val liked: Boolean,
)
