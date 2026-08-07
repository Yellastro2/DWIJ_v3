package com.yellastrodev.dwij.desktop.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.ui.playlist.PlaylistGridPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Desktop-адаптер разрешений/синхронизации для сетки плейлистов.
 */
@Composable
fun rememberDesktopPlaylistGridPlatform(
    component: DwijComponent,
    scope: CoroutineScope,
): PlaylistGridPlatform =
    remember(
        component,
        scope,
    ) {
        object :
            PlaylistGridPlatform {

            override fun hasLocalMusicAccess():
                Boolean =
                true

            override suspend fun requestLocalMusicAccess():
                Boolean =
                true

            override fun startLocalLibrarySync() {
                scope.launch {
                    component
                        .localMusicRepository
                        .synchronize(
                            force =
                                true,
                        )
                }
            }
        }
    }
