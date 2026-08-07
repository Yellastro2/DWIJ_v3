package com.yellastrodev.dwij.desktop.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.navigation.LocalLibraryPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Desktop-системные действия экрана локальной медиатеки.
 */
@Composable
fun rememberDesktopLocalLibraryPlatform(
    component: DwijComponent,
    scope: CoroutineScope,
): LocalLibraryPlatform =
    remember(
        component,
        scope,
    ) {
        object :
            LocalLibraryPlatform {

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

            override fun showTrackHideFailed() {
                component.logger.warning(
                    TAG,
                    "Не удалось изменить видимость локального трека",
                )
            }
        }
    }

private const val TAG =
    "DesktopLocalLibraryPlatform"
