package com.yellastrodev.dwij.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.yellastrodev.dwij.FullPlayerScreen
import com.yellastrodev.dwij.FullPlayerUiState
import com.yellastrodev.dwij.MultiSourceDialog
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.TrackListItemUiModel
import com.yellastrodev.dwij.TrackSourceIndicator
import com.yellastrodev.dwij.TrackSourceOptionUiModel
import com.yellastrodev.dwij.activities.MainActivity
import com.yellastrodev.dwij.data.entities.MusicSource
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.SongMatchCandidateEntity
import com.yellastrodev.dwij.data.entities.TrackInstance
import com.yellastrodev.dwij.data.entities.dYaLikeTracklist.Companion.KIND_LIKED
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Связывает полноэкранный Compose-плеер с общим activity-scoped [com.yellastrodev.dwij.models.PlayerModel].
 * Старый XML-плеер больше не создаётся, но очередь, Media3-команды и экран добавления в плейлист остаются прежними.
 */
class BigPlayerFrag : Fragment() {
    private val playerModel by lazy { (requireActivity() as MainActivity).playerModel }
    private val songMatchRepository by lazy {
        (requireActivity().application as yApplication).songMatchRepository
    }
    private val songRepository by lazy {
        (requireActivity().application as yApplication).songRepository
    }

    /** Создаёт единственный ComposeView и переводит состояния репозиториев в неизменяемую UI-модель. */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val track by playerModel.track.collectAsState()
            val playbackTrack by playerModel.playbackTrack.collectAsState()
            val playerState by playerModel.playerState.collectAsState()
            val playedTracklist by playerModel.playdTracklist.collectAsState()
            val shuffleBlocked by playerModel.shuffleBlock.collectAsState()
            val allPlaylists by playerModel.playlistRepo.playlists.collectAsState()
            val scope = rememberCoroutineScope()
            val uiMessages = remember { MutableSharedFlow<String>(extraBufferCapacity = 1) }
            var cover by remember(track?.id, playbackTrack?.instanceId) {
                mutableStateOf<ImageBitmap?>(null)
            }

            LaunchedEffect(track?.id, playbackTrack?.instanceId) {
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

            LaunchedEffect(
                showMultiSourceDialog,
                currentTrackId,
                pendingMatchCandidates,
            ) {
                if (!showMultiSourceDialog || currentTrackId == null) return@LaunchedEffect
                val relatedSongIds = pendingMatchCandidates
                    .flatMap { candidate ->
                        listOf(candidate.firstSongId, candidate.secondSongId)
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
                    Log.w(
                        TAG,
                        "[loadMultiSourceCandidates] Не удалось загрузить варианты songId=$currentTrackId",
                        error,
                    )
                    emptyList()
                }
            }
            val yandexTrack = track?.yandexInstances?.firstOrNull()?.track
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
            val fallbackQueueTitle = getString(R.string.player_current_queue)
            val unknownArtist = getString(R.string.home_player_unknown_artist)
            val sourceEntries = remember(track, candidateSongs) {
                (listOfNotNull(track) + candidateSongs)
                    .distinctBy(Song::id)
                    .flatMap { song ->
                        song.instances.map { instance -> SourceDialogEntry(song, instance) }
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
                            artist = entry.song.artistNames
                                .joinToString(", ")
                                .ifBlank { unknownArtist },
                            isYandexUnavailable =
                                (entry.instance as? TrackInstance.Yandex)
                                    ?.track
                                    ?.available == false,
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
                MusicSource.YANDEX -> getString(R.string.player_source_yandex)
                MusicSource.LOCAL -> getString(R.string.player_source_local)
                null -> null
            }
            val album = track?.albumTitle?.takeIf(String::isNotBlank)

            FullPlayerScreen(
                state = FullPlayerUiState(
                    trackId = currentTrackId,
                    queueTitle = playedTracklist?.getDTitle()
                        ?.takeIf(String::isNotBlank)
                        ?: fallbackQueueTitle,
                    queuePosition = (playerState.currentIndex + 1).coerceAtLeast(1),
                    title = track?.title ?: getString(R.string.player_no_track),
                    artist = track?.artistNames
                        ?.joinToString(", ")
                        ?.takeIf(String::isNotBlank)
                        ?: unknownArtist,
                    album = album,
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
                ),
                playerEvents = playerModel.playerEvent,
                uiMessages = uiMessages,
                onBackClick = { findNavController().navigateUp() },
                onPlayPauseClick = playerModel::playAudio,
                onPreviousClick = {
                    scope.launch { playerModel.prevTrack() }
                },
                onNextClick = {
                    scope.launch { playerModel.nextTrack() }
                },
                onSeek = playerModel::seekTo,
                onShuffleClick = playerModel::shuffle,
                onRepeatClick = playerModel::rotate,
                onLikeClick = {
                    scope.launch {
                        try {
                            playerModel.likeTrack()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Log.w(TAG, "[likeTrack] Не удалось изменить лайк", error)
                        }
                    }
                },
                onAddToPlaylistClick = ::openPlaylistPicker,
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
                        if (selectedEntries.size < 2 || isMergingSources) {
                            return@onSave
                        }
                        scope.launch {
                            isMergingSources = true
                            mergeSourcesError = null
                            try {
                                val sourceSongIds = selectedEntries
                                    .mapTo(linkedSetOf(), SourceDialogEntry::songId)
                                val (mergedSongId, mergedSong) = withContext(Dispatchers.IO) {
                                    val resultId = songRepository.mergeInstances(
                                        selectedEntries.map(SourceDialogEntry::instance),
                                    )
                                    val resultSong = requireNotNull(
                                        songRepository.songsByIds(listOf(resultId)).firstOrNull()
                                    ) {
                                        "Объединённая Song $resultId не найдена"
                                    }
                                    resultId to resultSong
                                }
                                playerModel.applyMergedSong(sourceSongIds, mergedSong)
                                showMultiSourceDialog = false
                                uiMessages.emit(
                                    getString(
                                        R.string.multi_source_merge_success,
                                        mergedSongId,
                                    )
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                Log.e(TAG, "[mergeSources] Не удалось объединить источники", error)
                                mergeSourcesError = getString(
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
    }

    /** Открывает существующий режим выбора плейлиста для текущего трека Яндекс Музыки. */
    private fun openPlaylistPicker() {
        val track = playerModel.track.value
        val yandexTrackId = track
            ?.yandexInstances
            ?.firstOrNull()
            ?.track
            ?.id
            ?: return
        val arguments = Bundle().apply {
            putString(GridPlaylistFrag.PLAYLIST_ACTION, GridPlaylistFrag.ACTION_ADDTRACK)
            putString(GridPlaylistFrag.ACTION_DATA, yandexTrackId)
        }
        findNavController().navigate(R.id.gridPlaylistFrag, arguments)
    }

    private companion object {
        const val TAG = "BigPlayerFrag"
    }
}

/** Связывает показанный source-инстанс с его исходной Song для последующего объединения. */
private data class SourceDialogEntry(
    val song: Song,
    val instance: TrackInstance,
) {
    val songId: String
        get() = song.id
}
