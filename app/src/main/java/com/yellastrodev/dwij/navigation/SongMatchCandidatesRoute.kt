package com.yellastrodev.dwij.navigation

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.yellastrodev.dwij.MultiSourceDialog
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.SongMatchCandidatesScreen
import com.yellastrodev.dwij.TrackListItemUiModel
import com.yellastrodev.dwij.TrackSourceIndicator
import com.yellastrodev.dwij.TrackSourceOptionUiModel
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.SongMatchCandidateEntity
import com.yellastrodev.dwij.data.entities.SongMatchCandidateStatus
import com.yellastrodev.dwij.data.entities.TrackInstance
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.yApplication
import com.yellastrodev.yandexmusiclib.entities.CoverSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Compose-route общего списка кандидатов на объединение источников. */
@Composable
fun SongMatchCandidatesRoute(
    navController: NavHostController,
    playerModel: PlayerModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val application = context.applicationContext as yApplication
    val candidates by application.songMatchRepository.candidates.collectAsState(initial = null)
    val currentCandidates = candidates.orEmpty()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val unknownArtist = stringResource(R.string.home_player_unknown_artist)
    val pendingLabel = stringResource(R.string.song_match_candidate_pending)
    val rejectedLabel = stringResource(R.string.song_match_candidate_rejected)
    var songsById by remember { mutableStateOf(emptyMap<String, Song>()) }
    var selectedCandidateKey by remember { mutableStateOf<String?>(null) }
    var isMergingSources by remember(selectedCandidateKey) { mutableStateOf(false) }
    var mergeSourcesError by remember(selectedCandidateKey) { mutableStateOf<String?>(null) }

    fun showSnackbar(message: String) {
        coroutineScope.launch { snackbarHostState.showSnackbar(message) }
    }

    LaunchedEffect(currentCandidates) {
        val songIds = currentCandidates
            .flatMap { candidate -> listOf(candidate.firstSongId, candidate.secondSongId) }
            .distinct()
        songsById = try {
            withContext(Dispatchers.IO) {
                application.songRepository.songsByIds(songIds).associateBy(Song::id)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "[loadCandidateSongs] Не удалось загрузить Song", error)
            showSnackbar(context.getString(R.string.song_match_candidates_load_error))
            emptyMap()
        }
    }

    val candidatesByKey = remember(currentCandidates) {
        currentCandidates.associateBy { candidate -> candidate.candidateRouteKey }
    }
    val listCoverSongs = remember(currentCandidates, songsById) {
        currentCandidates.mapNotNull { candidate ->
            songsById[candidate.firstSongId]?.let { song ->
                candidate.candidateRouteKey to song
            }
        }.toMap()
    }
    val items = remember(
        currentCandidates,
        songsById,
        unknownArtist,
        pendingLabel,
        rejectedLabel,
    ) {
        currentCandidates.mapNotNull { candidate ->
            val first = songsById[candidate.firstSongId] ?: return@mapNotNull null
            val second = songsById[candidate.secondSongId] ?: return@mapNotNull null
            val firstArtists = first.artistNames.joinToString(", ").ifBlank { unknownArtist }
            val secondArtists = second.artistNames.joinToString(", ").ifBlank { unknownArtist }
            val isPending = candidate.status == SongMatchCandidateStatus.PENDING.name
            TrackListItemUiModel(
                key = candidate.candidateRouteKey,
                trackId = candidate.candidateRouteKey,
                title = if (first.title.equals(second.title, ignoreCase = true)) {
                    first.title
                } else {
                    "${first.title}  ↔  ${second.title}"
                },
                artist = "${if (isPending) pendingLabel else rejectedLabel} · " +
                    "$firstArtists ↔ $secondArtists",
                shouldLoadCover = first.coverUri != null,
                hasUnresolvedMatchCandidate = isPending,
            )
        }
    }

    suspend fun loadListCover(song: Song): ImageBitmap? = withContext(Dispatchers.IO) {
        song.yandexInstances.firstOrNull()?.let { instance ->
            return@withContext application.coverRepository
                .getCover(instance.track, CoverSize.`100x100`)
                .asImageBitmap()
        }
        val source = song.localInstances.firstOrNull()
            ?.let { instance ->
                application.coverRepository.getCoverFlow(instance.track).firstOrNull()
            }
            ?: return@withContext null
        val largestSide = maxOf(source.width, source.height)
        val bitmap = if (largestSide > LIST_COVER_SIZE_PX) {
            val scale = LIST_COVER_SIZE_PX.toFloat() / largestSide
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            source
        }
        bitmap.asImageBitmap()
    }

    Box(modifier = modifier.fillMaxSize()) {
        SongMatchCandidatesScreen(
            items = items,
            onBackClick = { navController.navigateUp() },
            onItemClick = { item ->
                mergeSourcesError = null
                selectedCandidateKey = item.key
            },
            loadCover = { candidateKey ->
                listCoverSongs[candidateKey]?.let { song -> loadListCover(song) }
            },
            isLoading = candidates == null ||
                (currentCandidates.isNotEmpty() && items.size < currentCandidates.size),
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
        )
    }

    val selectedCandidate = selectedCandidateKey?.let(candidatesByKey::get)
    val selectedSongs = remember(selectedCandidate, songsById) {
        selectedCandidate?.let { candidate ->
            listOfNotNull(
                songsById[candidate.firstSongId],
                songsById[candidate.secondSongId],
            ).distinctBy(Song::id)
        }.orEmpty()
    }
    val sourceEntries = remember(selectedSongs) {
        selectedSongs.flatMap { song ->
            song.instances.map { instance -> CandidateSourceEntry(song, instance) }
        }.distinctBy { entry -> entry.instance.id }
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

    if (selectedCandidate != null && selectedSongs.size == 2) {
        MultiSourceDialog(
            options = sourceOptions,
            loadCover = { instanceId ->
                sourceInstancesById[instanceId]
                    ?.let { instance -> playerModel.cover(instance) }
                    ?.firstOrNull()
                    ?.asImageBitmap()
            },
            onDismiss = { selectedCandidateKey = null },
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
                            .mapTo(linkedSetOf(), CandidateSourceEntry::songId)
                        val (mergedSongId, mergedSong) = withContext(Dispatchers.IO) {
                            val resultId = application.songRepository.mergeInstances(
                                selectedEntries.map(CandidateSourceEntry::instance),
                            )
                            val resultSong = requireNotNull(
                                application.songRepository
                                    .songsByIds(listOf(resultId))
                                    .firstOrNull(),
                            ) { "Объединённая Song $resultId не найдена" }
                            resultId to resultSong
                        }
                        playerModel.applyMergedSong(sourceSongIds, mergedSong)
                        selectedCandidateKey = null
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

/** Связывает source-инстанс диалога с Song до транзакционного merge. */
private data class CandidateSourceEntry(
    val song: Song,
    val instance: TrackInstance,
) {
    val songId: String
        get() = song.id
}

private val SongMatchCandidateEntity.candidateRouteKey: String
    get() = "$firstSongId:$secondSongId"

private const val LIST_COVER_SIZE_PX = 180
private const val TAG = "SongMatchCandidatesRoute"
