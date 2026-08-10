package com.yellastrodev.dwij.desktop.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import com.yellastrodev.dwij.desktop.DesktopPaths
import com.yellastrodev.dwij.desktop.DesktopMusicDirectoryStore
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.navigation.DwijAppPlatform
import com.yellastrodev.dwij.navigation.HomeRoutePlatform
import com.yellastrodev.dwij.navigation.LocalLibraryPlatform
import com.yellastrodev.dwij.navigation.SettingsPlatform
import com.yellastrodev.dwij.ui.HomeScreenPlatform
import com.yellastrodev.dwij.ui.NoOpHomeScreenPlatform
import com.yellastrodev.dwij.ui.playlist.PlaylistGridPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Windows-набор платформенных адаптеров для shared DwijApp.
 */
class DesktopDwijAppPlatform(
    private val component: DwijComponent,
    private val applicationScope: CoroutineScope,
    private val paths: DesktopPaths,
    private val musicDirectoryStore: DesktopMusicDirectoryStore,
) : DwijAppPlatform {

    override val homeScreenPlatform:
        HomeScreenPlatform =
        NoOpHomeScreenPlatform

    /**
     * Подключает оконные команды desktop-версии после дочерних компонентов.
     * Поэтому ввод пробела в сфокусированном текстовом поле имеет приоритет.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    override fun globalInputModifier(
        hasActiveTrack: Boolean,
        onPlayPause: () -> Unit,
        onPrevious: () -> Unit,
        onNext: () -> Unit,
        onBack: () -> Unit,
    ): Modifier =
        Modifier
            .onKeyEvent { event ->
                handleDesktopPlaybackKeyEvent(
                    event =
                        event,
                    hasActiveTrack =
                        hasActiveTrack,
                    onPlayPause =
                        onPlayPause,
                    onPrevious =
                        onPrevious,
                    onNext =
                        onNext,
                )
            }
            .onPointerEvent(
                eventType =
                    PointerEventType.Press,
            ) { event ->
                if (event.buttons.isBackPressed) {
                    onBack()

                    event.changes.forEach { change ->
                        change.consume()
                    }
                }
            }

    /**
     * Обрабатывает непоглощённые события окна, даже когда внутри Compose
     * временно нет сфокусированного компонента.
     */
    fun handleWindowKeyEvent(
        event: KeyEvent,
    ): Boolean =
        handleDesktopPlaybackKeyEvent(
            event =
                event,
            hasActiveTrack =
                component.playerRepo
                    .currentSong
                    .value != null,
            onPlayPause = {
                component.playerRepo
                    .pause()
            },
            onPrevious = {
                applicationScope.launch {
                    component.playerRepo
                        .skipPrev()
                }
            },
            onNext = {
                applicationScope.launch {
                    component.playerRepo
                        .skipNext()
                }
            },
        )

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
            paths =
                paths,
            musicDirectoryStore =
                musicDirectoryStore,
        )
}

private enum class DesktopPlaybackAction {
    PLAY_PAUSE,
    PREVIOUS,
    NEXT,
}

private fun handleDesktopPlaybackKeyEvent(
    event: KeyEvent,
    hasActiveTrack: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): Boolean {
    val action =
        event.desktopPlaybackAction(
            hasActiveTrack =
                hasActiveTrack,
        )

    return when {
        action == null ->
            false

        event.type == KeyEventType.KeyDown ->
            true

        event.type == KeyEventType.KeyUp -> {
            when (action) {
                DesktopPlaybackAction.PLAY_PAUSE ->
                    onPlayPause()

                DesktopPlaybackAction.PREVIOUS ->
                    onPrevious()

                DesktopPlaybackAction.NEXT ->
                    onNext()
            }

            true
        }

        else ->
            false
    }
}

/** Определяет desktop-команду плеера без запуска повтора при удержании. */
private fun KeyEvent.desktopPlaybackAction(
    hasActiveTrack: Boolean,
): DesktopPlaybackAction? {
    if (!hasActiveTrack) {
        return null
    }

    val hasNoModifiers =
        !isCtrlPressed &&
            !isAltPressed &&
            !isMetaPressed &&
            !isShiftPressed

    val hasOnlyControl =
        isCtrlPressed &&
            !isAltPressed &&
            !isMetaPressed &&
            !isShiftPressed

    return when {
        key == Key.Spacebar && hasNoModifiers ->
            DesktopPlaybackAction.PLAY_PAUSE

        key == Key.DirectionLeft && hasOnlyControl ->
            DesktopPlaybackAction.PREVIOUS

        key == Key.DirectionRight && hasOnlyControl ->
            DesktopPlaybackAction.NEXT

        else ->
            null
    }
}
