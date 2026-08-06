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
import com.yellastrodev.dwij.YA_ID
import com.yellastrodev.dwij.YA_LOGIN
import com.yellastrodev.dwij.YA_REFRESH_TOKEN
import com.yellastrodev.dwij.YA_TOKEN
import com.yellastrodev.dwij.YA_TOKEN_EXPIRES_AT
import com.yellastrodev.dwij.yApplication

/** Android-реализация постоянного хранилища и внешних действий настроек. */
@Composable
fun rememberAndroidSettingsPlatform(): SettingsPlatform {
    val application =
        LocalContext.current.applicationContext as yApplication

    return remember(application) {
        AndroidSettingsPlatform(application)
    }
}

private class AndroidSettingsPlatform(
    private val application: yApplication,
) : SettingsPlatform {

    private val context: Context = application.applicationContext

    private val preferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    override val oauthClientId: String
        get() = BuildConfig.YANDEX_OAUTH_CLIENT_ID

    override val oauthClientSecret: String
        get() = BuildConfig.YANDEX_OAUTH_CLIENT_SECRET

    override fun readYandexLogin(): String? =
        preferences
            .getString(YA_TOKEN, "")
            ?.takeIf(String::isNotEmpty)
            ?.let {
                preferences.getString(
                    YA_LOGIN,
                    DEFAULT_LOGIN,
                ) ?: DEFAULT_LOGIN
            }

    override fun saveYandexSession(
        session: SettingsYandexSession,
    ) {
        val editor = preferences.edit()
            .putString(
                YA_TOKEN,
                session.accessToken,
            )
            .putString(
                YA_LOGIN,
                session.login,
            )
            .putString(
                YA_ID,
                session.userId,
            )

        if (session.refreshToken == null) {
            editor.remove(YA_REFRESH_TOKEN)
        } else {
            editor.putString(
                YA_REFRESH_TOKEN,
                session.refreshToken,
            )
        }

        if (session.expiresAtMillis == null) {
            editor.remove(YA_TOKEN_EXPIRES_AT)
        } else {
            editor.putLong(
                YA_TOKEN_EXPIRES_AT,
                session.expiresAtMillis!!,
            )
        }

        editor.apply()

        application.yamClient.updateAuthorization(
            token = session.accessToken,
            userId = session.userId,
            login = session.login,
        )
    }

    override fun clearYandexSession() {
        preferences.edit()
            .remove(YA_TOKEN)
            .remove(YA_REFRESH_TOKEN)
            .remove(YA_TOKEN_EXPIRES_AT)
            .remove(YA_LOGIN)
            .remove(YA_ID)
            .apply()

        application.yamClient.clearAuthorization()
    }

    override fun readCacheLimitBytes(): Long =
        preferences.getLong(
            CACHE_SIZE,
            DEFAULT_CACHE_SIZE,
        )

    override fun writeCacheLimitBytes(bytes: Long) {
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
            .getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(
                ClipData.newPlainText(
                    label,
                    text,
                ),
            )
    }

    override fun openUrl(url: String): Boolean =
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
        } catch (error: ActivityNotFoundException) {
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
        val lifecycleOwner = LocalLifecycleOwner.current
        val currentOnResume = rememberUpdatedState(onResume)

        DisposableEffect(lifecycleOwner) {
            var firstResume = true

            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    if (firstResume) {
                        firstResume = false
                    } else {
                        currentOnResume.value()
                    }
                }
            }

            lifecycleOwner.lifecycle.addObserver(observer)

            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }

    private companion object {
        const val TAG = "AndroidSettingsPlatform"
        const val DEFAULT_LOGIN = "nologin"
    }
}
