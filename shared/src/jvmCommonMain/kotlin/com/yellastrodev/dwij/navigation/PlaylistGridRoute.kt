package com.yellastrodev.dwij.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.resources.Res
import com.yellastrodev.dwij.resources.local_playlist_dwij
import com.yellastrodev.dwij.resources.local_playlist_m3u
import com.yellastrodev.dwij.resources.local_playlist_media_store
import com.yellastrodev.dwij.resources.local_playlists_empty
import com.yellastrodev.dwij.resources.playlists_add_track_title
import com.yellastrodev.dwij.resources.playlists_create
import com.yellastrodev.dwij.resources.playlists_empty_yandex
import com.yellastrodev.dwij.resources.playlists_liked
import com.yellastrodev.dwij.resources.playlists_title
import com.yellastrodev.dwij.ui.playlist.PlaylistGridContent
import com.yellastrodev.dwij.ui.playlist.PlaylistGridDependencies
import com.yellastrodev.dwij.ui.playlist.PlaylistGridPlatform
import com.yellastrodev.dwij.ui.playlist.PlaylistGridTexts
import com.yellastrodev.dwij.ui.playlist.PlaylistGridRoute as SharedPlaylistGridRoute
import org.jetbrains.compose.resources.stringResource

/**
 * Shared-entry экрана плейлистов.
 *
 * Собирает route-зависимости из общего компонента и использует
 * Compose Multiplatform resources. Платформа передаёт только
 * разрешения, синхронизацию и действия навигации.
 */
@Composable
fun PlaylistGridRoute(
    component: DwijComponent,
    platform: PlaylistGridPlatform,
    trackToAdd: String? = null,
    modifier: Modifier = Modifier,
) {
    val dependencies =
        remember(component) {
            PlaylistGridDependencies(
                playlistRepository =
                    component.playlistRepository,
                trackRepository =
                    component.trackRepository,
                coverRepository =
                    component.coverRepository,
                localMusicRepository =
                    component.localMusicRepository,
                musicSourceSelectionStore =
                    component.musicSourceSelectionStore,
            )
        }

    val texts =
        PlaylistGridTexts(
            title =
                stringResource(
                    Res.string.playlists_title,
                ),
            addTrackTitle =
                stringResource(
                    Res.string.playlists_add_track_title,
                ),
            create =
                stringResource(
                    Res.string.playlists_create,
                ),
            liked =
                stringResource(
                    Res.string.playlists_liked,
                ),
            localDwij =
                stringResource(
                    Res.string.local_playlist_dwij,
                ),
            localMediaStore =
                stringResource(
                    Res.string.local_playlist_media_store,
                ),
            localM3u =
                stringResource(
                    Res.string.local_playlist_m3u,
                ),
            localEmpty =
                stringResource(
                    Res.string.local_playlists_empty,
                ),
            yandexEmpty =
                stringResource(
                    Res.string.playlists_empty_yandex,
                ),
        )

    SharedPlaylistGridRoute(
        trackToAdd = trackToAdd,
        dependencies = dependencies,
        platform = platform,
        texts = texts,
        content = {
                state,
                actions,
                contentModifier,
            ->

            PlaylistGridContent(
                state = state,
                actions = actions,
                modifier = contentModifier,
            )
        },
        modifier = modifier,
    )
}