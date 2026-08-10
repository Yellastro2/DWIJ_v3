package com.yellastrodev.dwij.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.yellastrodev.dwij.desktop.models.DesktopPlayerCoverLoader
import com.yellastrodev.dwij.desktop.navigation.DesktopDwijAppPlatform
import com.yellastrodev.dwij.desktop.windows.WindowsTaskbarControls
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.navigation.DwijApp
import com.yellastrodev.dwij.ui.LocalPlayerVolumeControl
import com.yellastrodev.dwij.ui.LocalYamLogger
import dwij_v3.desktopapp.generated.resources.Res
import dwij_v3.desktopapp.generated.resources.dwij
import org.jetbrains.compose.resources.painterResource
import kotlin.math.roundToLong

/**
 * Desktop/Windows entry point.
 */
fun main() {
    val runtime =
        DesktopRuntime.create()

    val initialWindowWidthDp =
        runtime.settingsStore
            .getLong(
                WINDOW_WIDTH_KEY,
            )
            ?.coerceIn(
                MIN_WINDOW_WIDTH_DP,
                MAX_WINDOW_WIDTH_DP,
            )
            ?: DEFAULT_WINDOW_WIDTH_DP

    val initialWindowHeightDp =
        runtime.settingsStore
            .getLong(
                WINDOW_HEIGHT_KEY,
            )
            ?.coerceIn(
                MIN_WINDOW_HEIGHT_DP,
                MAX_WINDOW_HEIGHT_DP,
            )
            ?: DEFAULT_WINDOW_HEIGHT_DP

    val component =
        runtime.component

    val playerModel =
        PlayerModel(
            playerRepo =
                component.playerRepo,
            playlistRepo =
                component.playlistRepository,
            coverLoader =
                DesktopPlayerCoverLoader(
                    coverRepository =
                        component.coverRepository,
                    metadataReader =
                        runtime.audioMetadataReader,
                ),
        )

    val platform =
        DesktopDwijAppPlatform(
            component =
                component,
            applicationScope =
                runtime.applicationScope,
            paths =
                runtime.paths,
            musicDirectoryStore =
                runtime.musicDirectoryStore,
        )

    val taskbarControls =
        WindowsTaskbarControls(
            scope =
                runtime.applicationScope,
            logger =
                component.logger,
            cacheDirectory =
                runtime.paths
                    .cacheDirectory,
            playerState =
                component.playerRepo
                    .state,
            onPrevious = {
                component.playerRepo
                    .skipPrev()
            },
            onPlayPause = {
                component.playerRepo
                    .pause()
            },
            onNext = {
                component.playerRepo
                    .skipNext()
            },
        )

    application {
        val windowState =
            rememberWindowState(
                position =
                    WindowPosition.Aligned(
                        Alignment.Center,
                    ),
                width =
                    initialWindowWidthDp
                        .toInt()
                        .dp,
                height =
                    initialWindowHeightDp
                        .toInt()
                        .dp,
            )

        /*
         * Сначала создаём native Window невидимым.
         *
         * Так WindowsTaskbarControls успевает установить WndProc
         * ДО появления taskbar button и не пропускает
         * TaskbarButtonCreated.
         */
        var windowVisible by
        remember {
            mutableStateOf(
                false,
            )
        }

        Window(
            onCloseRequest = {
                /*
                 * Сначала восстанавливаем исходный WndProc,
                 * пока HWND ещё существует.
                 */
                taskbarControls.close()

                runtime.settingsStore.edit {
                    putLong(
                        WINDOW_WIDTH_KEY,
                        windowState
                            .size
                            .width
                            .value
                            .roundToLong(),
                    )

                    putLong(
                        WINDOW_HEIGHT_KEY,
                        windowState
                            .size
                            .height
                            .value
                            .roundToLong(),
                    )
                }

                /*
                 * Сохраняет volume и закрывает playback/SMTC.
                 */
                runtime.close()

                exitApplication()
            },
            onKeyEvent =
                platform::handleWindowKeyEvent,
            state =
                windowState,
            visible =
                windowVisible,
            title =
                "DWIJ",
            resizable =
                true,
            icon =
                painterResource(
                    Res.drawable.dwij,
                ),
        ) {
            DisposableEffect(
                window,
            ) {
                /*
                 * Устанавливаем WndProc пока окно ещё hidden.
                 */
                taskbarControls.attach(
                    window,
                )

                /*
                 * Теперь можно показать окно.
                 *
                 * Windows создаст taskbar button и пришлёт
                 * TaskbarButtonCreated уже нашему WndProc.
                 */
                windowVisible =
                    true

                onDispose {
                    taskbarControls.close()
                }
            }

            CompositionLocalProvider(
                LocalYamLogger provides
                        component.logger,
                LocalPlayerVolumeControl provides
                        runtime.playerVolumeControl,
            ) {
                DwijApp(
                    playerModel =
                        playerModel,
                    component =
                        component,
                    platform =
                        platform,
                )
            }
        }
    }
}

private const val WINDOW_WIDTH_KEY =
    "desktop.window.width.dp"

private const val WINDOW_HEIGHT_KEY =
    "desktop.window.height.dp"

private const val DEFAULT_WINDOW_WIDTH_DP =
    360L

private const val DEFAULT_WINDOW_HEIGHT_DP =
    820L

private const val MIN_WINDOW_WIDTH_DP =
    320L

private const val MIN_WINDOW_HEIGHT_DP =
    600L

private const val MAX_WINDOW_WIDTH_DP =
    1200L

private const val MAX_WINDOW_HEIGHT_DP =
    900L
