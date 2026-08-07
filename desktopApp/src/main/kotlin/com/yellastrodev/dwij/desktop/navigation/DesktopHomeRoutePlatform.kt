package com.yellastrodev.dwij.desktop.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.navigation.HomeRoutePlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Desktop не требует Android runtime-permission на чтение пользовательской music folder.
 */
@Composable
fun rememberDesktopHomeRoutePlatform(
    component: DwijComponent,
    scope: CoroutineScope,
): HomeRoutePlatform =
    remember(
        component,
        scope,
    ) {
        object :
            HomeRoutePlatform {

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
