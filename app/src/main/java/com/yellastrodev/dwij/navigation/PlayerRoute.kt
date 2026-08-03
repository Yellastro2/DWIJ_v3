package com.yellastrodev.dwij.navigation

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import com.yellastrodev.dwij.FullPlayerScreen
import com.yellastrodev.dwij.FullPlayerUiState
import com.yellastrodev.dwij.MultiSourceDialog
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.TrackListItemUiModel
import com.yellastrodev.dwij.TrackSourceIndicator
import com.yellastrodev.dwij.TrackSourceOptionUiModel
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.MusicSource
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.SongMatchCandidateEntity
import com.yellastrodev.dwij.data.entities.TrackInstance
import com.yellastrodev.dwij.data.entities.dYaLikeTracklist.Companion.KIND_LIKED
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Compose-route полноэкранного плеера и диалога выбора источников текущего трека. */
@Composable
fun PlayerRoute(
    navController: NavHostController,
    playerModel: PlayerModel,
) {
    val context = LocalContext.current
    val application = context.applicationContext as yApplication
    val songMatchRepository = application.songMatchRepository
    val songRepository = application.songRepository
    val track by playerModel.track.collectAsState()
    val playbackTrack by playerModel.playbackTrack.collectAsState()
    val playerState by playerModel.playerState.collectAsState()
    val playedTracklist by playerModel.playdTracklist.collectAsState()
    val shuffleBlocked by playerModel.shuffleBlock.collectAsState()
    val allPlaylists by playerModel.playlistRepo.playlists.collectAsState()
    val isWaveLoading by application.waveRepository.isLoading.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val uiMessages = remember { MutableSharedFlow<String>(extraBufferCapacity = 1) }
    var cover by remember(track?.id, playbackTrack?.instanceId) {
        mutableStateOf<ImageBitmap?>(null)
    }

    LaunchedEffect(track?.id, playbackTrack?.instanceId) {
        cover = null
        val currentTrack = track ?: return@LaunchedEffect
        try {
            playerModel.cover(currentTrack)
                .flowOn(Dispatchers.IO)
                .collect { bitmap -> cover = bitmap.asImageBitmap() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(
                TAG,
                "[loadPlayerCover] Не удалось загрузить обложку trackId=${currentTrack.id}",
                error,
            )
        }
    }

    val currentTrackId = track?.id
    var showMultiSourceDialog by remember(currentTrackId) { mutableStateOf(false) }
    var candidateSongs by remember(currentTrackId) { mutableStateOf(emptyList<Song>()) }
    var isMergingSources by remember(currentTrackId) { mutableStateOf(false) }
    var mergeSourcesError by remember(currentTrackId) { mutableStateOf<String?>(null) }
    val pendingMatchCandidates by remember(currentTrackId) {
        currentTrackId?.let(songMatchRepository::pendingCandidatesForSong)
            ?: flowOf(emptyList<SongMatchCandidateEntity>())
    }.collectAsState(initial = emptyList())

    LaunchedEffect(showMultiSourceDialog, currentTrackId, pendingMatchCandidates) {
        if (!showMultiSourceDialog || currentTrackId == null) return@LaunchedEffect
        val relatedSongIds = pendingMatchCandidates
            .flatMap { candidate -> listOf(candidate.firstSongId, candidate.secondSongId) }
            .filterNot { songId -> songId == currentTrackId }
            .distinct()
        candidateSongs = try {
            withContext(Dispatchers.IO) { songRepository.songsByIds(relatedSongIds) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(
                TAG,
                "[loadMultiSourceCandidates] Не удалось загрузить варианты " +
                    "songId=$currentTrackId",
                error,
            )
            emptyList()
        }
    }

    val yandexTrack = track?.yandexInstances?.firstOrNull()?.track
    val yandexTrackId = yandexTrack?.id
    val playlistKeys = yandexTrack?.playlists.orEmpty()
    val containingPlaylists = remember(currentTrackId, yandexTrackId, playlistKeys, allPlaylists) {
        if (currentTrackId == null || yandexTrack == null) {
            emptyList()
        } else {
            allPlaylists.filter { playlist ->
                playlist.kind != KIND_LIKED &&
                    (playlist.playlistUuid in playlistKeys ||
                        playlist.tracks.any { it.trackId == yandexTrackId })
            }
        }
    }
    val isLiked = remember(currentTrackId, yandexTrackId, allPlaylists) {
        yandexTrackId != null && allPlaylists
            .firstOrNull { it.kind == KIND_LIKED }
            ?.tracks
            ?.any { it.trackId == yandexTrackId } == true
    }
    val containingPlaylistTitles = remember(containingPlaylists) {
        containingPlaylists.map { it.title }
    }
    val fallbackQueueTitle = stringResource(R.string.player_current_queue)
    val unknownArtist = stringResource(R.string.home_player_unknown_artist)
    val waveLoadingTitle = stringResource(R.string.player_wave_loading_title)
    val waveLoadingArtist = stringResource(R.string.player_wave_loading_artist)
    val showWaveLoadingPlaceholder = isWaveLoading && currentTrackId == null
    val sourceEntries = remember(track, candidateSongs) {
        (listOfNotNull(track) + candidateSongs)
            .distinctBy(Song::id)
            .flatMap { song ->
                song.instances.map { instance -> PlayerSourceDialogEntry(song, instance) }
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
    val sourceLabel = when (playbackTrack?.source) {
        MusicSource.YANDEX -> stringResource(R.string.player_source_yandex)
        MusicSource.LOCAL -> stringResource(R.string.player_source_local)
        null -> null
    }

    FullPlayerScreen(
        state = FullPlayerUiState(
            trackId = currentTrackId,
            queueTitle = if (showWaveLoadingPlaceholder) {
                waveLoadingTitle
            } else {
                playedTracklist?.getDTitle()?.takeIf(String::isNotBlank)
                    ?: fallbackQueueTitle
            },
            queuePosition = (playerState.currentIndex + 1).coerceAtLeast(1),
            title = if (showWaveLoadingPlaceholder) {
                waveLoadingTitle
            } else {
                track?.title ?: stringResource(R.string.player_no_track)
            },
            artist = if (showWaveLoadingPlaceholder) {
                waveLoadingArtist
            } else {
                track?.artistNames
                    ?.joinToString(", ")
                    ?.takeIf(String::isNotBlank)
                    ?: unknownArtist
            },
            album = track?.albumTitle?.takeIf(String::isNotBlank),
            sourceLabel = sourceLabel,
            hasMultipleSources = (track?.instances?.size ?: 0) > 1,
            hasUnresolvedMatchCandidate = pendingMatchCandidates.isNotEmpty(),
            cover = cover,
            isPlaying = playerState.isPlaying,
            currentPositionMillis = playerState.currentPosition,
            durationMillis = playerState.duration,
            isShuffle = playerState.isShuffle,
            isRepeatAll = playerState.isRepeatAll,
            showPlaybackModes = !shuffleBlocked,
            canLike = yandexTrack != null,
            isLiked = isLiked,
            playlistTitles = containingPlaylistTitles,
            isWaveLoading = showWaveLoadingPlaceholder,
        ),
        playerEvents = playerModel.playerEvent,
        uiMessages = uiMessages,
        onBackClick = { navController.navigateUp() },
        onPlayPauseClick = playerModel::playAudio,
        onPreviousClick = { coroutineScope.launch { playerModel.prevTrack() } },
        onNextClick = { coroutineScope.launch { playerModel.nextTrack() } },
        onSeek = playerModel::seekTo,
        onShuffleClick = playerModel::shuffle,
        onRepeatClick = playerModel::rotate,
        onLikeClick = {
            coroutineScope.launch {
                try {
                    when (val result = playerModel.likeTrack()) {
                        is DataResult.Success -> Log.d(
                            TAG,
                            "[likeTrack] Лайк текущего трека успешно изменён",
                        )
                        is DataResult.Failure -> {
                            Log.e(TAG, "[likeTrack] Лайк не изменён: ${result.error}")
                            uiMessages.emit(context.getString(R.string.player_like_failed))
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "[likeTrack] Не удалось изменить лайк", error)
                }
            }
        },
        onAddToPlaylistClick = {
            yandexTrackId?.let { trackId ->
                navController.navigate(DwijDestination.playlistsAddRoute(trackId))
            }
        },
        onSourcesClick = {
            mergeSourcesError = null
            showMultiSourceDialog = true
        },
    )

    if (showMultiSourceDialog) {
        MultiSourceDialog(
            options = sourceOptions,
            loadCover = { instanceId ->
                sourceInstancesById[instanceId]
                    ?.let { instance -> playerModel.cover(instance) }
                    ?.firstOrNull()
                    ?.asImageBitmap()
            },
            onDismiss = { showMultiSourceDialog = false },
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
                            .mapTo(linkedSetOf(), PlayerSourceDialogEntry::songId)
                        val (mergedSongId, mergedSong) = withContext(Dispatchers.IO) {
                            val resultId = songRepository.mergeInstances(
                                selectedEntries.map(PlayerSourceDialogEntry::instance),
                            )
                            val resultSong = requireNotNull(
                                songRepository.songsByIds(listOf(resultId)).firstOrNull(),
                            ) { "Объединённая Song $resultId не найдена" }
                            resultId to resultSong
                        }
                        playerModel.applyMergedSong(sourceSongIds, mergedSong)
                        showMultiSourceDialog = false
                        uiMessages.emit(
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

/** Связывает отображаемый source-инстанс с исходной Song до объединения. */
private data class PlayerSourceDialogEntry(
    val song: Song,
    val instance: TrackInstance,
) {
    val songId: String
        get() = song.id
}

private const val TAG = "PlayerRoute"
