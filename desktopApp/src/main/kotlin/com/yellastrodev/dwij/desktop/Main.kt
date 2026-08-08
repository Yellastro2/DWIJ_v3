package com.yellastrodev.dwij.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.yellastrodev.dwij.desktop.models.DesktopPlayerCoverLoader
import com.yellastrodev.dwij.desktop.navigation.DesktopDwijAppPlatform
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.navigation.DwijApp
import com.yellastrodev.dwij.ui.LocalPlayerVolumeControl
import com.yellastrodev.dwij.ui.LocalYamLogger
import dwij_v3.desktopapp.generated.resources.Res
import dwij_v3.desktopapp.generated.resources.dwij
import org.jetbrains.compose.resources.painterResource

/**
 * Desktop/Windows entry point.
 */
fun main() {
    val runtime =
        DesktopRuntime.create()

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

    application {
        val windowState =
            rememberWindowState(
                width =
                    520.dp,
                height =
                    900.dp,
            )

        Window(
            onCloseRequest = {
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