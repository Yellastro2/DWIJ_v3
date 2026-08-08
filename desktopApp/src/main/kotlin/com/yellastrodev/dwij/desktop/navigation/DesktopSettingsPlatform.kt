package com.yellastrodev.dwij.desktop.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.yellastrodev.dwij.desktop.DesktopPaths
import com.yellastrodev.dwij.navigation.SettingsPlatform
import java.awt.Desktop
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.beans.PropertyChangeListener
import java.io.File
import java.net.URI
import java.util.Properties

/**
 * Windows/JVM-внешние действия экрана настроек.
 */
@Composable
fun rememberDesktopSettingsPlatform(
    paths: DesktopPaths,
): SettingsPlatform =
    remember(
        paths,
    ) {
        DesktopSettingsPlatform(
            paths,
        )
    }

private class DesktopSettingsPlatform(
    private val paths: DesktopPaths,
) : SettingsPlatform {

    private val localProperties:
        Properties by lazy {
            Properties().apply {
                val file =
                    File(
                        System.getProperty(
                            "user.dir",
                        ),
                        "local.properties",
                    )

                if (file.isFile) {
                    file.inputStream()
                        .buffered()
                        .use(
                            ::load,
                        )
                }
            }
        }

    override val oauthClientId: String
        get() =
            configValue(
                systemProperty =
                    "dwij.yandex.clientId",
                environmentVariable =
                    "DWIJ_YANDEX_OAUTH_CLIENT_ID",
                localProperty =
                    "YANDEX_OAUTH_CLIENT_ID",
            )

    override val oauthClientSecret: String
        get() =
            configValue(
                systemProperty =
                    "dwij.yandex.clientSecret",
                environmentVariable =
                    "DWIJ_YANDEX_OAUTH_CLIENT_SECRET",
                localProperty =
                    "YANDEX_OAUTH_CLIENT_SECRET",
            )

    override fun availableCacheBytes():
        Long =
        paths.cacheDirectory
            .usableSpace

    override fun copyText(
        label: String,
        text: String,
    ) {
        Toolkit
            .getDefaultToolkit()
            .systemClipboard
            .setContents(
                StringSelection(
                    text,
                ),
                null,
            )
    }

    override fun openUrl(
        url: String,
    ): Boolean =
        runCatching {
            require(
                Desktop.isDesktopSupported(),
            )

            val desktop =
                Desktop.getDesktop()

            require(
                desktop.isSupported(
                    Desktop.Action.BROWSE,
                ),
            )

            desktop.browse(
                URI(url),
            )
        }.isSuccess

    @Composable
    override fun ResumeEffect(
        onResume: () -> Unit,
    ) {
        val currentOnResume =
            rememberUpdatedState(
                onResume,
            )

        DisposableEffect(Unit) {
            val focusManager =
                KeyboardFocusManager
                    .getCurrentKeyboardFocusManager()

            val listener =
                PropertyChangeListener { event ->
                    if (
                        event.newValue !=
                        null
                    ) {
                        currentOnResume
                            .value()
                    }
                }

            focusManager
                .addPropertyChangeListener(
                    "activeWindow",
                    listener,
                )

            onDispose {
                focusManager
                    .removePropertyChangeListener(
                        "activeWindow",
                        listener,
                    )
            }
        }
    }

    private fun configValue(
        systemProperty: String,
        environmentVariable: String,
        localProperty: String,
    ): String =
        System.getProperty(
            systemProperty,
        )
            ?.takeIf(String::isNotBlank)
            ?: System.getenv(
                environmentVariable,
            )
                ?.takeIf(String::isNotBlank)
            ?: localProperties
                .getProperty(
                    localProperty,
                    "",
                )
}
