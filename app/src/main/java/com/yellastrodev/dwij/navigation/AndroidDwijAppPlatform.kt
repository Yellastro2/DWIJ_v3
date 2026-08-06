package com.yellastrodev.dwij.navigation

import androidx.compose.runtime.Composable
import com.yellastrodev.dwij.AndroidHomeScreenPlatform
import com.yellastrodev.dwij.ui.HomeScreenPlatform
import com.yellastrodev.dwij.ui.playlist.PlaylistGridPlatform

/** Android-набор системных реализаций для shared [DwijApp]. */
object AndroidDwijAppPlatform : DwijAppPlatform {
    override val homeScreenPlatform: HomeScreenPlatform =
        AndroidHomeScreenPlatform

    @Composable
    override fun rememberHomeRoutePlatform(): HomeRoutePlatform =
        rememberAndroidHomeRoutePlatform()

    @Composable
    override fun rememberPlaylistGridPlatform(): PlaylistGridPlatform =
        rememberAndroidPlaylistGridPlatform()

    @Composable
    override fun rememberLocalLibraryPlatform(): LocalLibraryPlatform =
        rememberAndroidLocalLibraryPlatform()

    @Composable
    override fun rememberSettingsPlatform(): SettingsPlatform =
        rememberAndroidSettingsPlatform()
}
