package com.yellastrodev.dwij.desktop.navigation

import androidx.compose.runtime.Composable
import com.yellastrodev.dwij.desktop.DesktopPaths
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.navigation.DwijAppPlatform
import com.yellastrodev.dwij.navigation.HomeRoutePlatform
import com.yellastrodev.dwij.navigation.LocalLibraryPlatform
import com.yellastrodev.dwij.navigation.SettingsPlatform
import com.yellastrodev.dwij.ui.HomeScreenPlatform
import com.yellastrodev.dwij.ui.NoOpHomeScreenPlatform
import com.yellastrodev.dwij.ui.playlist.PlaylistGridPlatform
import kotlinx.coroutines.CoroutineScope

/**
 * Windows-набор платформенных адаптеров для shared DwijApp.
 */
class DesktopDwijAppPlatform(
    private val component: DwijComponent,
    private val applicationScope: CoroutineScope,
    private val paths: DesktopPaths,
) : DwijAppPlatform {

    override val homeScreenPlatform:
        HomeScreenPlatform =
        NoOpHomeScreenPlatform

    @Composable
    override fun rememberHomeRoutePlatform():
        HomeRoutePlatform =
        rememberDesktopHomeRoutePlatform(
            component =
                component,
            scope =
                applicationScope,
        )

    @Composable
    override fun rememberPlaylistGridPlatform():
        PlaylistGridPlatform =
        rememberDesktopPlaylistGridPlatform(
            component =
                component,
            scope =
                applicationScope,
        )

    @Composable
    override fun rememberLocalLibraryPlatform():
        LocalLibraryPlatform =
        rememberDesktopLocalLibraryPlatform(
            component =
                component,
            scope =
                applicationScope,
        )

    @Composable
    override fun rememberSettingsPlatform():
        SettingsPlatform =
        rememberDesktopSettingsPlatform(
            paths,
        )
}
