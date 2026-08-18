package com.yellastrodev.dwij.navigation

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yellastrodev.dwij.BuildConfig
import com.yellastrodev.dwij.util.AppSessionLogStore
import com.yellastrodev.dwij.work.LocalCatalogResolveWorker

/**
 * Android-внешние действия экрана настроек.
 */
@Composable
fun rememberAndroidSettingsPlatform(): SettingsPlatform {
    val context =
        LocalContext.current
            .applicationContext

    return remember(context) {
        AndroidSettingsPlatform(
            context,
        )
    }
}

private class AndroidSettingsPlatform(
    private val context: Context,
) : SettingsPlatform {

    override val appVersion: String
        get() =
            BuildConfig.VERSION_NAME

    override val oauthClientId: String
        get() =
            BuildConfig.YANDEX_OAUTH_CLIENT_ID

    override val oauthClientSecret: String
        get() =
            BuildConfig.YANDEX_OAUTH_CLIENT_SECRET

    override val canShareLogs: Boolean
        get() = true

    override fun availableCacheBytes(): Long =
        StatFs(
            context.cacheDir.absolutePath,
        ).availableBytes

    override fun copyText(
        label: String,
        text: String,
    ) {
        context
            .getSystemService(
                ClipboardManager::class.java,
            )
            .setPrimaryClip(
                ClipData.newPlainText(
                    label,
                    text,
                ),
            )
    }

    override fun openUrl(
        url: String,
    ): Boolean =
        try {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(url),
                ).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK,
                ),
            )

            true
        } catch (
            error: ActivityNotFoundException,
        ) {
            Log.e(
                TAG,
                "[openUrl] Не найден браузер для страницы авторизации",
                error,
            )

            false
        }

    /** Создаёт ZIP последних сессий и передаёт его выбранному приложению на чтение. */
    override suspend fun shareLogs(
        chooserTitle: String,
    ) {
        Log.i(
            TAG,
            "[shareLogs] Готовим архив журналов приложения для отправки",
        )

        val archive =
            AppSessionLogStore.createShareArchive(
                context,
            )

        val archiveUri =
            FileProvider.getUriForFile(
                context,
                "${BuildConfig.APPLICATION_ID}.dwij-files",
                archive,
            )

        val shareIntent =
            Intent(
                Intent.ACTION_SEND,
            )
                .setType(
                    "application/zip",
                )
                .putExtra(
                    Intent.EXTRA_STREAM,
                    archiveUri,
                )
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )

        shareIntent.clipData =
            ClipData.newUri(
                context.contentResolver,
                archive.name,
                archiveUri,
            )

        context.startActivity(
            Intent.createChooser(
                shareIntent,
                chooserTitle,
            ).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK,
            ),
        )
    }

    override fun onYandexAuthorizationSaved() {
        LocalCatalogResolveWorker.enqueue(context)
    }

    @Composable
    override fun ResumeEffect(
        onResume: () -> Unit,
    ) {
        val lifecycleOwner =
            LocalLifecycleOwner.current

        val currentOnResume =
            rememberUpdatedState(
                onResume,
            )

        DisposableEffect(
            lifecycleOwner,
        ) {
            var firstResume = true

            val observer =
                LifecycleEventObserver {
                        _,
                        event,
                    ->

                    if (
                        event ==
                        Lifecycle.Event.ON_RESUME
                    ) {
                        if (firstResume) {
                            firstResume = false
                        } else {
                            currentOnResume
                                .value()
                        }
                    }
                }

            lifecycleOwner
                .lifecycle
                .addObserver(
                    observer,
                )

            onDispose {
                lifecycleOwner
                    .lifecycle
                    .removeObserver(
                        observer,
                    )
            }
        }
    }

    private companion object {
        const val TAG =
            "AndroidSettingsPlatform"
    }
}
