package com.yellastrodev.dwij.navigation

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.preference.PreferenceManager
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yellastrodev.dwij.BuildConfig
import com.yellastrodev.dwij.CACHE_SIZE
import com.yellastrodev.dwij.DEFAULT_CACHE_SIZE
import com.yellastrodev.dwij.auth.YandexSession
import com.yellastrodev.dwij.yApplication

/** Android-внешние действия и настройки дискового кэша. */
@Composable
fun rememberAndroidSettingsPlatform(): SettingsPlatform {
    val application =
        LocalContext.current.applicationContext
            as yApplication

    return remember(application) {
        AndroidSettingsPlatform(application)
    }
}

private class AndroidSettingsPlatform(
    private val application: yApplication,
) : SettingsPlatform {

    private val context: Context =
        application.applicationContext

    private val preferences =
        PreferenceManager
            .getDefaultSharedPreferences(context)

    private val sessionManager
        get() =
            application
                .component
                .yandexSessionManager

    override val oauthClientId: String
        get() =
            BuildConfig.YANDEX_OAUTH_CLIENT_ID

    override val oauthClientSecret: String
        get() =
            BuildConfig.YANDEX_OAUTH_CLIENT_SECRET

    override fun readYandexLogin(): String? =
        sessionManager.currentLogin()

    override fun saveYandexSession(
        session: SettingsYandexSession,
    ) {
        sessionManager.save(
            YandexSession(
                accessToken =
                    session.accessToken,
                refreshToken =
                    session.refreshToken,
                expiresAtMillis =
                    session.expiresAtMillis,
                login =
                    session.login,
                userId =
                    session.userId,
            ),
        )
    }

    override fun clearYandexSession() {
        sessionManager.clear()
    }

    override fun readCacheLimitBytes(): Long =
        preferences.getLong(
            CACHE_SIZE,
            DEFAULT_CACHE_SIZE,
        )

    override fun writeCacheLimitBytes(
        bytes: Long,
    ) {
        preferences.edit()
            .putLong(
                CACHE_SIZE,
                bytes,
            )
            .apply()
    }

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

    @Composable
    override fun ResumeEffect(
        onResume: () -> Unit,
    ) {
        val lifecycleOwner =
            LocalLifecycleOwner.current

        val currentOnResume =
            rememberUpdatedState(onResume)

        DisposableEffect(lifecycleOwner) {
            var firstResume = true

            val observer =
                LifecycleEventObserver { _, event ->
                    if (
                        event ==
                        Lifecycle.Event.ON_RESUME
                    ) {
                        if (firstResume) {
                            firstResume = false
                        } else {
                            currentOnResume.value()
                        }
                    }
                }

            lifecycleOwner.lifecycle
                .addObserver(observer)

            onDispose {
                lifecycleOwner.lifecycle
                    .removeObserver(observer)
            }
        }
    }

    private companion object {
        const val TAG =
            "AndroidSettingsPlatform"
    }
}
