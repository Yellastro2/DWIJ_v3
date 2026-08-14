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

    /** Возвращает платформенный запуск надёжного сохранения ЯМ-трека. */
    @Composable
    fun rememberLocalTrackDownloadRequester(): LocalTrackDownloadRequester

    /** Возвращает платформенное системное действие «Поделиться». */
    @Composable
    fun rememberShareRequester(): ShareRequester
}

/** Платформенно ставит один ЯМ-трек в очередь постоянного сохранения. */
fun interface LocalTrackDownloadRequester {
    fun request(trackId: String, title: String)
}

/** Передаёт ссылку системному share-механизму платформы. */
fun interface ShareRequester {
    fun share(url: String)
}
