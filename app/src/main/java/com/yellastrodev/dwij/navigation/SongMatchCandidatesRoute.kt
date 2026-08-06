package com.yellastrodev.dwij.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.yellastrodev.dwij.ui.MultiSourceDialog
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.ui.SongMatchCandidatesScreen
import com.yellastrodev.dwij.TrackListItemUiModel
import com.yellastrodev.dwij.ui.TrackSourceIndicator
import com.yellastrodev.dwij.ui.TrackSourceOptionUiModel
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.SongMatchCandidateStatus
import com.yellastrodev.dwij.data.entities.TrackInstance
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.models.SongMatchCandidatesEvent
import com.yellastrodev.dwij.models.SongMatchCandidatesModel
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.flow.firstOrNull

/** Compose-route общего списка кандидатов на объединение источников. */
@Composable
fun SongMatchCandidatesRoute(
    navController: NavHostController,
    playerModel: PlayerModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val application = context.applicationContext as yApplication

    val candidatesModel = viewModel<SongMatchCandidatesModel>(
        factory = SongMatchCandidatesModel.Factory(
            songMatchRepository = application.songMatchRepository,
            songRepository = application.songRepository,
            playerRepository = application.playerRepo,
        ),
    )

    val state by candidatesModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val unknownArtist = stringResource(R.string.home_player_unknown_artist)
    val pendingLabel = stringResource(R.string.song_match_candidate_pending)
    val rejectedLabel = stringResource(R.string.song_match_candidate_rejected)

    LaunchedEffect(candidatesModel, snackbarHostState) {
        candidatesModel.events.collect { event ->
            when (event) {
                SongMatchCandidatesEvent.LoadFailed -> {
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.song_match_candidates_load_error,
                        ),
                    )
                }

                is SongMatchCandidatesEvent.MergeSucceeded -> {
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.multi_source_merge_success,
                            event.mergedSongId,
                        ),
                    )
                }
            }
        }
    }

    val listCoverSongs = remember(state.candidates) {
        state.candidates.associate { candidate ->
            candidate.key to candidate.firstSong
        }
    }

    val items = remember(
        state.candidates,
        unknownArtist,
        pendingLabel,
        rejectedLabel,
    ) {
        state.candidates.map { candidate ->
            val first = candidate.firstSong
            val second = candidate.secondSong
            val firstArtists = first.artistNames
                .joinToString(", ")
                .ifBlank { unknownArtist }
            val secondArtists = second.artistNames
                .joinToString(", ")
                .ifBlank { unknownArtist }
            val isPending = candidate.status == SongMatchCandidateStatus.PENDING

            TrackListItemUiModel(
                key = candidate.key,
                trackId = candidate.key,
                title = if (
                    first.title.equals(
                        second.title,
                        ignoreCase = true,
                    )
                ) {
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

    suspend fun loadListCover(song: Song): ImageBitmap? =
        playerModel
            .cover(
                song = song,
                maxEdgePx = LIST_COVER_SIZE_PX,
            )
            .firstOrNull()

    Box(modifier = modifier.fillMaxSize()) {
        SongMatchCandidatesScreen(
            items = items,
            onBackClick = { navController.navigateUp() },
            onItemClick = { item ->
                candidatesModel.selectCandidate(item.key)
            },
            loadCover = { candidateKey ->
                listCoverSongs[candidateKey]
                    ?.let { song -> loadListCover(song) }
            },
            isLoading = state.isLoading,
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
        )
    }

    val sourceOptions = remember(
        state.selectedSourceEntries,
        unknownArtist,
    ) {
        state.selectedSourceEntries.map { entry ->
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

    val sourceInstancesById = remember(state.selectedSourceEntries) {
        state.selectedSourceEntries.associate { entry ->
            entry.instance.id to entry.instance
        }
    }

    val mergeErrorMessage = state.mergeError?.let { error ->
        context.getString(
            R.string.multi_source_merge_error,
            error.message ?: error.javaClass.simpleName,
        )
    }

    if (state.selectedCandidateKey != null) {
        MultiSourceDialog(
            options = sourceOptions,
            loadCover = { instanceId ->
                sourceInstancesById[instanceId]
                    ?.let { instance -> playerModel.cover(instance) }
                    ?.firstOrNull()
            },
            onDismiss = candidatesModel::dismissCandidate,
            onSave = candidatesModel::mergeSources,
            isSaving = state.isMergingSources,
            errorMessage = mergeErrorMessage,
        )
    }
}

private const val LIST_COVER_SIZE_PX = 180
