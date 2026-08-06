package com.yellastrodev.dwij.navigation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.yellastrodev.dwij.LocalLibraryScreen
import com.yellastrodev.dwij.LocalPlaylistObjectScreen
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntity
import com.yellastrodev.dwij.data.entities.LocalTracklist
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.work.LocalLibrarySyncWorker
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Compose-route локальных списков, всех треков и объекта локального плейлиста. */
@Composable
fun LocalLibraryRoute(
    navController: NavHostController,
    mode: String,
    playlistId: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val application = context.applicationContext as yApplication
    val repository = application.localMusicRepository
    val isSynchronizing by repository.isSynchronizing.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val allTracksTitle = stringResource(R.string.local_all_tracks_title)
    var hideConfirmationTrack by remember { mutableStateOf<Song?>(null) }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                LocalLibrarySyncWorker.enqueueImmediate(context.applicationContext)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    suspend fun loadTrackCover(
        song: Song,
    ): ImageBitmap = withContext(Dispatchers.IO) {
        val track = requireNotNull(
            song.localInstances.firstOrNull()?.track,
        ) {
            "У песни ${song.id} отсутствует локальный экземпляр"
        }

        val albumCover = track.albumId?.let { albumId ->
            runCatching {
                context.contentResolver.openInputStream(
                    Uri.parse(
                        "content://media/external/audio/albumart/$albumId",
                    ),
                )?.use { input ->
                    BitmapFactory.decodeStream(input)
                }
            }.getOrNull()
        }

        val source = albumCover ?: run {
            val retriever = MediaMetadataRetriever()

            try {
                retriever.setDataSource(
                    context,
                    Uri.parse(track.contentUri),
                )

                retriever.embeddedPicture?.let { bytes ->
                    BitmapFactory.decodeByteArray(
                        bytes,
                        0,
                        bytes.size,
                    )
                }
            } catch (error: Exception) {
                Log.d(
                    TAG,
                    "[loadTrackCover] Не удалось прочитать обложку ${track.instanceId}",
                    error,
                )
                null
            } finally {
                runCatching {
                    retriever.release()
                }
            }
        } ?: requireNotNull(
            ContextCompat.getDrawable(
                context,
                R.drawable.ic_play_logo,
            ),
        ).toBitmap()

        val largestSide = maxOf(
            source.width,
            source.height,
        )

        val displayBitmap = if (largestSide > TRACK_COVER_SIZE_PX) {
            val scale = TRACK_COVER_SIZE_PX.toFloat() / largestSide

            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            source
        }

        displayBitmap.asImageBitmap()
    }

    fun openPlaylist(playlist: LocalPlaylistEntity) {
        navController.navigate(
            DwijDestination.localLibraryRoute(
                mode = DwijDestination.LOCAL_MODE_PLAYLIST,
                playlistId = playlist.playlistId,
            ),
        )
    }

    fun playTracks(tracks: List<Song>, index: Int, tracklist: LocalTracklist) {
        if (index !in tracks.indices) return
        coroutineScope.launch {
            Log.d(
                TAG,
                "[playTracks] Запускаем локальную очередь: index=$index, size=${tracks.size}",
            )
            application.playerRepo.playQueue(tracks, index, tracklist)
            navController.navigate(DwijDestination.PLAYER)
        }
    }

    fun requestTrackHide(song: Song) {
        if (song.localInstances.isEmpty()) {
            Log.w(TAG, "[requestTrackHide] У Song ${song.id} нет локального экземпляра")
            return
        }
        hideConfirmationTrack = song
    }

    fun hideTrack(song: Song) {
        val localInstanceIds = song.localInstances.map { instance -> instance.id }
        if (localInstanceIds.isEmpty()) return
        coroutineScope.launch {
            try {
                when (val result = repository.setTracksHidden(localInstanceIds, isHidden = true)) {
                    is DataResult.Success -> Log.d(
                        TAG,
                        "[hideTrack] Скрыт '${song.title}', instances=${localInstanceIds.size}",
                    )
                    is DataResult.Failure -> {
                        Log.e(TAG, "[hideTrack] Не удалось скрыть '${song.title}': ${result.error}")
                        Toast.makeText(
                            context,
                            R.string.local_track_hide_failed,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "[hideTrack] Непредвиденная ошибка для '${song.title}'", error)
                Toast.makeText(
                    context,
                    R.string.local_track_hide_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    when {
        mode == DwijDestination.LOCAL_MODE_PLAYLISTS -> {
            val playlists by repository.playlists.collectAsState(initial = null)
            LocalLibraryScreen(
                title = stringResource(R.string.local_playlists_title),
                playlists = playlists.orEmpty(),
                tracks = null,
                onPlaylistClick = ::openPlaylist,
                onTrackClick = { _, _ -> },
                onTrackHideRequest = {},
                loadTrackCover = ::loadTrackCover,
                isLoading = playlists == null ||
                    (playlists.isNullOrEmpty() && isSynchronizing),
                modifier = modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .background(LocalLibraryBackground),
            )
        }
        mode == DwijDestination.LOCAL_MODE_PLAYLIST && playlistId != null -> {
            val playlist by repository.playlist(playlistId).collectAsState(initial = null)
            val tracks by repository.playlistSongs(playlistId).collectAsState(initial = null)
            val loadedTracks = tracks.orEmpty()
            val playlistTitle = playlist?.name ?: stringResource(R.string.local_playlist_title)
            LocalPlaylistObjectScreen(
                title = playlistTitle,
                tracks = loadedTracks,
                onBackClick = { navController.navigateUp() },
                onPlayClick = {
                    playTracks(
                        tracks = loadedTracks,
                        index = 0,
                        tracklist = LocalTracklist(id = playlistId, name = playlistTitle),
                    )
                },
                loadTrackCover = ::loadTrackCover,
                onTrackClick = { index, _ ->
                    playTracks(
                        tracks = loadedTracks,
                        index = index,
                        tracklist = LocalTracklist(id = playlistId, name = playlistTitle),
                    )
                },
                onTrackHideRequest = ::requestTrackHide,
                isLoading = tracks == null ||
                    (tracks.isNullOrEmpty() && isSynchronizing),
                modifier = modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .background(LocalLibraryBackground),
            )
        }
        else -> {
            val tracks by repository.songs.collectAsState(initial = null)
            val loadedTracks = tracks.orEmpty()
            LocalLibraryScreen(
                title = allTracksTitle,
                playlists = null,
                tracks = loadedTracks,
                onPlaylistClick = {},
                loadTrackCover = ::loadTrackCover,
                onTrackClick = { index, _ ->
                    playTracks(
                        tracks = loadedTracks,
                        index = index,
                        tracklist = LocalTracklist(id = "local:all", name = allTracksTitle),
                    )
                },
                onTrackHideRequest = ::requestTrackHide,
                isLoading = tracks == null ||
                    (tracks.isNullOrEmpty() && isSynchronizing),
                modifier = modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .background(LocalLibraryBackground),
            )
        }
    }

    hideConfirmationTrack?.let { track ->
        AlertDialog(
            onDismissRequest = { hideConfirmationTrack = null },
            title = { Text(stringResource(R.string.local_track_hide_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.local_track_hide_confirm_message,
                        track.title,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        hideConfirmationTrack = null
                        hideTrack(track)
                    },
                ) {
                    Text(stringResource(R.string.local_track_hide_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { hideConfirmationTrack = null }) {
                    Text(stringResource(R.string.playlists_cancel))
                }
            },
        )
    }
}

private val LocalLibraryBackground = Color(0xFF101116)
private const val TRACK_COVER_SIZE_PX = 180
private const val TAG = "LocalLibraryRoute"
