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

/** Один элемент платформенной очереди постоянного сохранения. */
data class LocalTrackDownloadRequest(
    val trackId: String,
    val title: String,
)

/** Платформенно ставит один или несколько ЯМ-треков в очередь сохранения. */
fun interface LocalTrackDownloadRequester {
    fun request(trackId: String, title: String)

    fun requestAll(requests: List<LocalTrackDownloadRequest>) {
        requests.forEach { item ->
            request(item.trackId, item.title)
        }
    }
}

/** Передаёт ссылку системному share-механизму платформы. */
fun interface ShareRequester {
    fun share(url: String)
}
