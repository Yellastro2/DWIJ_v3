package com.yellastrodev.dwij.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.yellastrodev.dwij.MultiSourceDialog
import com.yellastrodev.dwij.ObjectScreen
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.TYPE
import com.yellastrodev.dwij.TrackListItemUiModel
import com.yellastrodev.dwij.TrackSourceIndicator
import com.yellastrodev.dwij.TrackSourceOptionUiModel
import com.yellastrodev.dwij.VALUE
import com.yellastrodev.dwij.activities.MainActivity
import com.yellastrodev.dwij.data.entities.SongMatchCandidateEntity
import com.yellastrodev.dwij.data.entities.TrackInstance
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.models.TracklistModel
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Полностью Compose-экран абстрактного музыкального объекта и его списка треков. */
class ObjectFrag : Fragment() {
    private val app: yApplication
        get() = requireActivity().application as yApplication
    private val playerModel by lazy { (requireActivity() as MainActivity).playerModel }
    private val songMatchRepository by lazy { app.songMatchRepository }
    private val songRepository by lazy { app.songRepository }
    private val model: TracklistModel by viewModels {
        TracklistModel.Factory(
            repo = app.playlistRepository,
            coverRepo = app.coverRepository,
            trackRepo = app.trackRepository,
            trackCacheRepo = app.trackCacheRepo,
            songRepo = app.songRepository,
            playerRepo = app.playerRepo,
            waveRepo = app.waveRepository,
        )
    }

    private var objectType = ""
    private var objectValue = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        objectType = arguments?.getString(TYPE).orEmpty()
        objectValue = arguments?.getString(VALUE).orEmpty()
        if (objectType.isNotBlank()) {
            model.setType(objectType, objectValue)
        }
    }

    /** Создаёт единственный ComposeView вместо старой XML-шапки и RecyclerView. */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val playlist by model.playlist.collectAsState()
            val tracks by model.tracks.collectAsState()
            val isLoading by model.isLoading.collectAsState()
            val cachedUnavailableSongIds by model.cachedUnavailableSongIds.collectAsState()
            val yandexPlaylist = playlist as? dYaPlaylist
            val objectId = yandexPlaylist?.playlistUuid
            val unknownArtist = stringResource(R.string.home_player_unknown_artist)
            val title = when {
                objectType == TRACKLIST -> stringResource(R.string.track_list_all_title)
                yandexPlaylist != null -> yandexPlaylist.title
                else -> stringResource(R.string.object_loading_title)
            }
            val count = yandexPlaylist?.trackCount ?: tracks.size
            val subtitle = pluralStringResource(
                R.plurals.object_track_count,
                count,
                count,
            )
            val trackItems = remember(tracks, cachedUnavailableSongIds, unknownArtist) {
                tracks.toTrackListItems(
                    unknownArtist = unknownArtist,
                    cachedUnavailableSongIds = cachedUnavailableSongIds,
                )
            }
            val listState = rememberLazyListState()
            val refreshScope = rememberCoroutineScope()
            var objectCover by remember(objectId) { mutableStateOf<ImageBitmap?>(null) }
            var isRefreshing by remember { mutableStateOf(false) }
            var sourceDialogSongId by remember { mutableStateOf<String?>(null) }
            var candidateSongs by remember(sourceDialogSongId) {
                mutableStateOf(emptyList<Song>())
            }
            var isMergingSources by remember(sourceDialogSongId) { mutableStateOf(false) }
            var mergeSourcesError by remember(sourceDialogSongId) { mutableStateOf<String?>(null) }
            val sourceDialogSong = remember(tracks, sourceDialogSongId) {
                tracks.firstOrNull { song -> song.id == sourceDialogSongId }
            }
            val pendingMatchCandidates by remember(sourceDialogSongId) {
                sourceDialogSongId?.let(songMatchRepository::pendingCandidatesForSong)
                    ?: flowOf(emptyList<SongMatchCandidateEntity>())
            }.collectAsState(initial = emptyList())

            LaunchedEffect(sourceDialogSongId, pendingMatchCandidates) {
                val currentSongId = sourceDialogSongId ?: return@LaunchedEffect
                val relatedSongIds = pendingMatchCandidates
                    .flatMap { candidate ->
                        listOf(candidate.firstSongId, candidate.secondSongId)
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
                model.scrollResetEvents.collect {
                    listState.scrollToItem(0)
                }
            }

            ObjectScreen(
                title = title,
                subtitle = subtitle,
                description = yandexPlaylist?.description,
                cover = objectCover,
                tracks = trackItems,
                listState = listState,
                showShare = objectType == PLAYLIST,
                showWave = objectType == PLAYLIST,
                emptyMessage = stringResource(R.string.track_list_empty),
                isLoading = isLoading,
                isRefreshing = isRefreshing,
                onRefresh = {
                    if (!isRefreshing) {
                        refreshScope.launch {
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
                loadTrackCover = { trackId ->
                    model.getTrackCover(trackId)?.asImageBitmap()
                },
                onBackClick = { findNavController().navigateUp() },
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
                        showUnavailableTrackSnackbar()
                    }
                },
                onTrackClick = { position, item ->
                    when {
                        item.isPlaybackBlocked &&
                            (item.hasMultipleSources || item.hasUnresolvedMatchCandidate) -> {
                            mergeSourcesError = null
                            sourceDialogSongId = item.trackId
                        }
                        item.isPlaybackBlocked -> showUnavailableTrackSnackbar()
                        else -> playTrack(position, item.trackId)
                    }
                },
                onShareClick = {
                    Log.d(TAG, "[shareObject] Поделиться объектом пока не подключено")
                },
                onWaveClick = ::playWave,
                modifier = Modifier.fillMaxSize(),
            )

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
                        refreshScope.launch {
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
                                        songRepository.songsByIds(listOf(resultId)).firstOrNull()
                                    ) {
                                        "Объединённая Song $resultId не найдена"
                                    }
                                    resultId to resultSong
                                }
                                model.applyMergedSong(sourceSongIds, mergedSong)
                                playerModel.applyMergedSong(sourceSongIds, mergedSong)
                                sourceDialogSongId = null
                                showMultiSourceMergeSuccessSnackbar(mergedSongId)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                Log.e(
                                    TAG,
                                    "[mergeSources] Не удалось объединить источники",
                                    error,
                                )
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

    /** Запускает очередь с выбранной позиции и открывает полный плеер. */
    private fun playTrack(position: Int, expectedSongId: String? = null) {
        if (model.onTrackClicked(position, expectedSongId)) {
            findNavController().navigate(R.id.action_objectFrag_to_bigPlayerFrag)
        }
    }

    /** Запускает волну для объекта и сразу открывает полный плеер. */
    private fun playWave() {
        viewLifecycleOwner.lifecycleScope.launch {
            model.playWave()
        }
        findNavController().navigate(R.id.bigPlayerFrag)
    }

    /** Создаёт уникальные LazyColumn-ключи для повторяющихся треков одного объекта. */
    private fun List<Song>.toTrackListItems(
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
                artist = song.artistNames
                    .joinToString(", ")
                    .ifBlank { unknownArtist },
                shouldLoadCover = song.coverUri != null,
                isYandexUnavailable = yandexUnavailable,
                isPlaybackBlocked = song.localInstances.isEmpty() && !yandexPlayable,
                hasMultipleSources = song.instances.size > 1,
                hasUnresolvedMatchCandidate = song.hasPendingMatchCandidate,
            )
        }
    }

    /** Показывает причину, по которой строка недоступного трека не открыла плеер. */
    private fun showUnavailableTrackSnackbar() {
        view?.let { root ->
            Snackbar.make(
                root,
                R.string.track_unavailable_yandex,
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    /** Показывает ID сохранённой мультисурсной Song после закрытия диалога. */
    private fun showMultiSourceMergeSuccessSnackbar(songId: String) {
        view?.let { root ->
            Snackbar.make(
                root,
                getString(R.string.multi_source_merge_success, songId),
                Snackbar.LENGTH_LONG,
            ).show()
        }
    }

    companion object {
        const val TRACK = "track"
        const val PLAYLIST = "playlist"
        const val TRACKLIST = "tracklist"
        const val ARTIST = "artist"
        private const val TAG = "ObjectFrag"
    }
}

/** Связывает вариант диалога со старой Song, к которой он относился до merge. */
private data class ObjectSourceDialogEntry(
    val song: Song,
    val instance: TrackInstance,
) {
    val songId: String
        get() = song.id
}
