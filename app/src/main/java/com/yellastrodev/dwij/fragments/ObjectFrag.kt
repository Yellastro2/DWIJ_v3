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
import com.yellastrodev.dwij.ObjectScreen
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.TYPE
import com.yellastrodev.dwij.TrackListItemUiModel
import com.yellastrodev.dwij.VALUE
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.models.TracklistModel
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Полностью Compose-экран абстрактного музыкального объекта и его списка треков. */
class ObjectFrag : Fragment() {
    private val model: TracklistModel by viewModels {
        TracklistModel.Factory(
            repo = (requireActivity().application as yApplication).playlistRepository,
            coverRepo = (requireActivity().application as yApplication).coverRepository,
            trackRepo = (requireActivity().application as yApplication).trackRepository,
            trackCacheRepo = (requireActivity().application as yApplication).trackCacheRepo,
            playerRepo = (requireActivity().application as yApplication).playerRepo,
            waveRepo = (requireActivity().application as yApplication).waveRepository,
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
            val cachedUnavailableTrackIds by model.cachedUnavailableTrackIds.collectAsState()
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
            val trackItems = remember(tracks, cachedUnavailableTrackIds, unknownArtist) {
                tracks.toTrackListItems(
                    unknownArtist = unknownArtist,
                    cachedUnavailableTrackIds = cachedUnavailableTrackIds,
                )
            }
            val listState = rememberLazyListState()
            val refreshScope = rememberCoroutineScope()
            var objectCover by remember(objectId) { mutableStateOf<ImageBitmap?>(null) }
            var isRefreshing by remember { mutableStateOf(false) }

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
                            expectedTrackId = trackItems[firstPlayableIndex].trackId,
                        )
                    } else if (trackItems.isNotEmpty()) {
                        showUnavailableTrackSnackbar()
                    }
                },
                onTrackClick = { position, item ->
                    if (item.isPlaybackBlocked) {
                        showUnavailableTrackSnackbar()
                    } else {
                        playTrack(position, item.trackId)
                    }
                },
                onShareClick = {
                    Log.d(TAG, "[shareObject] Поделиться объектом пока не подключено")
                },
                onWaveClick = ::playWave,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    /** Запускает очередь с выбранной позиции и открывает полный плеер. */
    private fun playTrack(position: Int, expectedTrackId: String? = null) {
        if (model.onTrackClicked(position, expectedTrackId)) {
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
    private fun List<dYaTrack>.toTrackListItems(
        unknownArtist: String,
        cachedUnavailableTrackIds: Set<String>,
    ): List<TrackListItemUiModel> {
        val occurrences = mutableMapOf<String, Int>()
        return map { track ->
            val occurrence = occurrences.getOrDefault(track.id, 0)
            occurrences[track.id] = occurrence + 1
            TrackListItemUiModel(
                key = "${track.id}:$occurrence",
                trackId = track.id,
                title = track.title,
                artist = track.artists
                    .joinToString(", ") { artist -> artist.name }
                    .ifBlank { unknownArtist },
                shouldLoadCover = track.getCoverUriAny() != null,
                isYandexUnavailable = !track.available,
                isPlaybackBlocked = !track.available &&
                    track.id !in cachedUnavailableTrackIds,
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

    companion object {
        const val TRACK = "track"
        const val PLAYLIST = "playlist"
        const val TRACKLIST = "tracklist"
        const val ARTIST = "artist"
        private const val TAG = "ObjectFrag"
    }
}
