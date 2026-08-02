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
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.activities.MainActivity
import com.yellastrodev.dwij.data.entities.MusicSource
import com.yellastrodev.dwij.data.entities.dYaLikeTracklist.Companion.KIND_LIKED
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * Связывает полноэкранный Compose-плеер с общим activity-scoped [com.yellastrodev.dwij.models.PlayerModel].
 * Старый XML-плеер больше не создаётся, но очередь, Media3-команды и экран добавления в плейлист остаются прежними.
 */
class BigPlayerFrag : Fragment() {
    private val playerModel by lazy { (requireActivity() as MainActivity).playerModel }

    /** Создаёт единственный ComposeView и переводит состояния репозиториев в неизменяемую UI-модель. */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val track by playerModel.track.collectAsState()
            val playerState by playerModel.playerState.collectAsState()
            val playedTracklist by playerModel.playdTracklist.collectAsState()
            val shuffleBlocked by playerModel.shuffleBlock.collectAsState()
            val allPlaylists by playerModel.playlistRepo.playlists.collectAsState()
            val scope = rememberCoroutineScope()
            var cover by remember(track?.id) { mutableStateOf<ImageBitmap?>(null) }

            LaunchedEffect(track?.id) {
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
            val yandexTrack = track?.yandexTrack
            val playlistKeys = yandexTrack?.playlists.orEmpty()
            val containingPlaylists = remember(currentTrackId, playlistKeys, allPlaylists) {
                if (currentTrackId == null || yandexTrack == null) {
                    emptyList()
                } else {
                    allPlaylists.filter { playlist ->
                        playlist.kind != KIND_LIKED &&
                            (playlist.playlistUuid in playlistKeys ||
                                playlist.tracks.any { it.trackId == currentTrackId })
                    }
                }
            }
            val isLiked = remember(currentTrackId, allPlaylists) {
                currentTrackId != null && allPlaylists
                    .firstOrNull { it.kind == KIND_LIKED }
                    ?.tracks
                    ?.any { it.trackId == currentTrackId } == true
            }
            val containingPlaylistTitles = remember(containingPlaylists) {
                containingPlaylists.map { it.title }
            }
            val fallbackQueueTitle = getString(R.string.player_current_queue)
            val unknownArtist = getString(R.string.home_player_unknown_artist)
            val sourceLabel = when (track?.source) {
                MusicSource.YANDEX -> getString(R.string.player_source_yandex)
                MusicSource.LOCAL -> getString(R.string.player_source_local)
                null -> null
            }
            val album = when (track?.source) {
                MusicSource.YANDEX -> yandexTrack?.albums
                    ?.joinToString(", ") { it.title }
                    ?.takeIf(String::isNotBlank)
                MusicSource.LOCAL -> track?.localTrack?.album?.takeIf(String::isNotBlank)
                null -> null
            }

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
                    cover = cover,
                    isPlaying = playerState.isPlaying,
                    currentPositionMillis = playerState.currentPosition,
                    durationMillis = playerState.duration,
                    isShuffle = playerState.isShuffle,
                    isRepeatAll = playerState.isRepeatAll,
                    showPlaybackModes = !shuffleBlocked,
                    canLike = track?.source == MusicSource.YANDEX,
                    isLiked = isLiked,
                    playlistTitles = containingPlaylistTitles,
                ),
                playerEvents = playerModel.playerEvent,
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
            )
        }
    }

    /** Открывает существующий режим выбора плейлиста для текущего трека Яндекс Музыки. */
    private fun openPlaylistPicker() {
        val track = playerModel.track.value
            ?.takeIf { it.source == MusicSource.YANDEX }
            ?: return
        val arguments = Bundle().apply {
            putString(GridPlaylistFrag.PLAYLIST_ACTION, GridPlaylistFrag.ACTION_ADDTRACK)
            putString(GridPlaylistFrag.ACTION_DATA, track.id)
        }
        findNavController().navigate(R.id.gridPlaylistFrag, arguments)
    }

    private companion object {
        const val TAG = "BigPlayerFrag"
    }
}
