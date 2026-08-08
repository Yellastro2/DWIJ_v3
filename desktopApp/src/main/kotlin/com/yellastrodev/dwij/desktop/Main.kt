package com.yellastrodev.dwij.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
        )

    /*
     * Windows thumbnail toolbar:
     *
     * Previous | Play/Pause | Next
     *
     * Никакой отдельной playback-логики здесь нет:
     * используем существующий PlayerRepository.
     */
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

        Window(
            onCloseRequest = {
                /*
                 * Сначала возвращаем исходный WndProc,
                 * пока native Window ещё существует.
                 */
                taskbarControls.close()

                /*
                 * Сохраняем размер окна как и раньше.
                 */
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
                 * Runtime отдельно сохраняет volume
                 * и закрывает PlayerEngine/SMTC.
                 */
                runtime.close()

                exitApplication()
            },
            state =
                windowState,
            title =
                "DWIJ",
            resizable =
                true,
            icon =
                painterResource(
                    Res.drawable.dwij,
                ),
        ) {
            /*
             * Здесь ComposeWindow уже реально создан,
             * поэтому JNA может получить его HWND.
             */
            DisposableEffect(
                window,
            ) {
                taskbarControls.attach(
                    window,
                )

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