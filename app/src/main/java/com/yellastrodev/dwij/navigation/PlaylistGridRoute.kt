package com.yellastrodev.dwij.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import com.yellastrodev.dwij.AndroidPlaylistGridContent
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.ui.playlist.PlaylistGridDependencies
import com.yellastrodev.dwij.ui.playlist.PlaylistGridTexts
import com.yellastrodev.dwij.ui.playlist.PlaylistGridRoute as SharedPlaylistGridRoute
import com.yellastrodev.dwij.yApplication

/** Android-вход в shared-экран плейлистов. */
@Composable
fun PlaylistGridRoute(
    navController: NavHostController,
    trackToAdd: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val application = context.applicationContext as yApplication
    val platform = rememberAndroidPlaylistGridPlatform(navController)
    val dependencies = remember(application) {
        PlaylistGridDependencies(
            playlistRepository = application.playlistRepository,
            trackRepository = application.trackRepository,
            coverRepository = application.coverRepository,
            localMusicRepository = application.localMusicRepository,
            musicSourceSelectionStore = application.musicSourceSelectionStore,
        )
    }
    val texts = PlaylistGridTexts(
        title = stringResource(R.string.playlists_title),
        addTrackTitle = stringResource(R.string.playlists_add_track_title),
        create = stringResource(R.string.playlists_create),
        liked = stringResource(R.string.playlists_liked),
        localDwij = stringResource(R.string.local_playlist_dwij),
        localMediaStore = stringResource(R.string.local_playlist_media_store),
        localM3u = stringResource(R.string.local_playlist_m3u),
        localEmpty = stringResource(R.string.local_playlists_empty),
        yandexEmpty = stringResource(R.string.playlists_empty_yandex),
    )

    SharedPlaylistGridRoute(
        trackToAdd = trackToAdd,
        dependencies = dependencies,
        platform = platform,
        texts = texts,
        content = { state, actions, contentModifier ->
            AndroidPlaylistGridContent(
                state = state,
                actions = actions,
                modifier = contentModifier,
            )
        },
        modifier = modifier,
    )
}