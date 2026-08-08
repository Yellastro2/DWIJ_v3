package com.yellastrodev.dwij.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yellastrodev.dwij.ui.HomeScreenPlatform
import com.yellastrodev.dwij.ui.playlist.PlaylistGridPlatform

/**
 * Набор платформенных возможностей, необходимых shared-корню приложения.
 *
 * Навигация не входит в этот контракт: NavController и все переходы принадлежат [DwijApp].
 */
interface DwijAppPlatform {
    val homeScreenPlatform: HomeScreenPlatform

    /**
     * Добавляет платформенные команды уровня всего окна приложения.
     *
     * Обработчик получает только события, которые не были поглощены
     * дочерним элементом, например активным полем ввода текста.
     */
    @Composable
    fun globalInputModifier(
        hasActiveTrack: Boolean,
        onPlayPause: () -> Unit,
        onPrevious: () -> Unit,
        onNext: () -> Unit,
        onBack: () -> Unit,
    ): Modifier = Modifier

    @Composable
    fun rememberHomeRoutePlatform(): HomeRoutePlatform

    @Composable
    fun rememberPlaylistGridPlatform(): PlaylistGridPlatform

    @Composable
    fun rememberLocalLibraryPlatform(): LocalLibraryPlatform

    @Composable
    fun rememberSettingsPlatform(): SettingsPlatform
}
