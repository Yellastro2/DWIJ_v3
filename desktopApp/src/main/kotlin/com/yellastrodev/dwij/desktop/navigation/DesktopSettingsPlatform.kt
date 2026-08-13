package com.yellastrodev.dwij.desktop.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.yellastrodev.dwij.desktop.DesktopPaths
import com.yellastrodev.dwij.desktop.DesktopMusicDirectoryStore
import com.yellastrodev.dwij.navigation.SettingsPlatform
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.beans.PropertyChangeListener
import java.io.File
import java.net.URI
import java.util.Properties
import javax.swing.JFileChooser
import javax.swing.UIManager

/**
 * Windows/JVM-внешние действия экрана настроек.
 */
@Composable
fun rememberDesktopSettingsPlatform(
    paths: DesktopPaths,
    musicDirectoryStore: DesktopMusicDirectoryStore,
): SettingsPlatform =
    remember(
        paths,
        musicDirectoryStore,
    ) {
        DesktopSettingsPlatform(
            paths =
                paths,
            musicDirectoryStore =
                musicDirectoryStore,
        )
    }

private class DesktopSettingsPlatform(
    private val paths: DesktopPaths,
    private val musicDirectoryStore: DesktopMusicDirectoryStore,
) : SettingsPlatform {

    override val appVersion: String
        get() =
            System.getProperty(
                "dwij.app.version",
                "unknown",
            )

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

    override val musicDirectories: List<String>
        get() =
            musicDirectoryStore
                .directories()
                .map(File::getAbsolutePath)

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

    override fun chooseMusicDirectory(
        dialogTitle: String,
    ): String? {
        var selectedDirectory: String? = null

        val showDialog = {
            runCatching {
                UIManager.setLookAndFeel(
                    UIManager.getSystemLookAndFeelClassName(),
                )
            }

            val chooser =
                JFileChooser().apply {
                    this.dialogTitle =
                        dialogTitle
                    fileSelectionMode =
                        JFileChooser.DIRECTORIES_ONLY
                    isAcceptAllFileFilterUsed =
                        false
                    isMultiSelectionEnabled =
                        false
                    currentDirectory =
                        musicDirectoryStore
                            .directories()
                            .firstOrNull()
                }

            val result =
                chooser.showOpenDialog(
                    KeyboardFocusManager
                        .getCurrentKeyboardFocusManager()
                        .activeWindow,
                )

            if (
                result ==
                JFileChooser.APPROVE_OPTION
            ) {
                selectedDirectory =
                    chooser.selectedFile
                        ?.takeIf(File::isDirectory)
                        ?.let { directory ->
                            runCatching {
                                directory.canonicalPath
                            }.getOrElse {
                                directory.absolutePath
                            }
                        }
            }
        }

        if (EventQueue.isDispatchThread()) {
            showDialog()
        } else {
            EventQueue.invokeAndWait {
                showDialog()
            }
        }

        return selectedDirectory
    }

    override fun replaceMusicDirectories(
        directories: List<String>,
    ): List<String> =
        musicDirectoryStore
            .replace(
                directories.map(::File),
            )
            .map(File::getAbsolutePath)

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
