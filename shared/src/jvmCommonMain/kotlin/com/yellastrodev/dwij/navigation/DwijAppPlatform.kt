package com.yellastrodev.dwij.navigation

import androidx.compose.runtime.Composable
import com.yellastrodev.dwij.ui.HomeScreenPlatform
import com.yellastrodev.dwij.ui.playlist.PlaylistGridPlatform

/**
 * Набор платформенных возможностей, необходимых shared-корню приложения.
 *
 * Навигация не входит в этот контракт: NavController и все переходы принадлежат [DwijApp].
 */
interface DwijAppPlatform {
    val homeScreenPlatform: HomeScreenPlatform

    @Composable
    fun rememberHomeRoutePlatform(): HomeRoutePlatform

    @Composable
    fun rememberPlaylistGridPlatform(): PlaylistGridPlatform

    @Composable
    fun rememberLocalLibraryPlatform(): LocalLibraryPlatform

    @Composable
    fun rememberSettingsPlatform(): SettingsPlatform
}
