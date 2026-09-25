package com.yellastrodev.dwij.activities

import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yellastrodev.dwij.models.AndroidPlayerCoverLoader
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.navigation.AndroidDwijAppPlatform
import com.yellastrodev.dwij.navigation.DwijApp
import com.yellastrodev.dwij.ui.LocalYamLogger
import com.yellastrodev.dwij.yApplication
import com.yellastrodev.dwij.R
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Android-точка входа приложения.
 *
 * Создаёт Android-зависимости и запускает полностью shared Compose-корень.
 */
class MainActivity : ComponentActivity() {

    private var showYandexLinksDialog by mutableStateOf(false)

    private val playerOpenRequests = Channel<Unit>(Channel.CONFLATED)
    private val playerOpenRequestFlow = playerOpenRequests.receiveAsFlow()
    private val yandexTrackRequests = Channel<String>(Channel.CONFLATED)
    private val yandexTrackRequestFlow = yandexTrackRequests.receiveAsFlow()

    val playerModel: PlayerModel by viewModels {
        viewModelFactory {
            initializer {
                val application =
                    this@MainActivity.application
                            as yApplication

                val component =
                    application.component

                PlayerModel(
                    playerRepo =
                        component.playerRepo,
                    playlistRepo =
                        component.playlistRepository,
                    coverLoader =
                        AndroidPlayerCoverLoader(
                            context =
                                application.applicationContext,
                            coverRepository =
                                component.coverRepository,
                        ),
                )
            }
        }
    }

    override fun onCreate(
        savedInstanceState: Bundle?,
    ) {
        super.onCreate(
            savedInstanceState,
        )

        if (savedInstanceState == null) {
            checkYandexLinksOnLaunch()
        }

        consumePlayerOpenIntent(intent)
        consumeYandexTrackIntent(intent)

        enableEdgeToEdge()

        WindowCompat
            .getInsetsController(
                window,
                window.decorView,
            )
            .apply {
                isAppearanceLightStatusBars =
                    false

                isAppearanceLightNavigationBars =
                    false
            }

        setContent {
            val dwijApplication =
                this@MainActivity.application
                        as yApplication

            CompositionLocalProvider(
                LocalYamLogger provides
                    dwijApplication
                        .component
                        .logger,
            ) {
                DwijApp(
                    playerModel =
                        playerModel,
                    component =
                        dwijApplication.component,
                    platform =
                        AndroidDwijAppPlatform,
                    playerOpenRequests =
                        playerOpenRequestFlow,
                    yandexTrackRequests =
                        yandexTrackRequestFlow,
                )
                if (showYandexLinksDialog) {
                    AlertDialog(
                        onDismissRequest = { showYandexLinksDialog = false },
                        title = { Text(getString(R.string.yandex_links_dialog_title)) },
                        text = { Text(getString(R.string.yandex_links_dialog_message)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showYandexLinksDialog = false
                                openLinkSettings()
                            }) {
                                Text(getString(R.string.yandex_links_dialog_settings))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showYandexLinksDialog = false }) {
                                Text(getString(R.string.yandex_links_dialog_later))
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumePlayerOpenIntent(intent)
        consumeYandexTrackIntent(intent)
    }

    /** Передаёт одноразовую команду системного медиаплеера в Compose-навигацию. */
    private fun consumePlayerOpenIntent(intent: Intent?) {
        if (intent?.action != ACTION_OPEN_PLAYER) return

        playerOpenRequests.trySend(Unit)
        intent.action = Intent.ACTION_MAIN
    }

    /** Извлекает ID трека из прямой ссылки или ссылки на трек внутри альбома. */
    private fun consumeYandexTrackIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (uri.scheme != "https" || uri.host != YANDEX_MUSIC_HOST) return

        val segments = uri.pathSegments
        val trackId = when {
            segments.size == 2 && segments[0] == "track" -> segments[1]
            segments.size == 4 && segments[0] == "album" &&
                segments[1].all(Char::isDigit) && segments[2] == "track" -> segments[3]
            else -> return
        }
        if (trackId.isBlank() || !trackId.all(Char::isDigit)) return

        yandexTrackRequests.trySend(trackId)
        intent.action = Intent.ACTION_MAIN
    }

    /** Считает открытия главного экрана и проверяет ссылки только при третьем и четвёртом запуске. */
    @Suppress("DEPRECATION")
    private fun checkYandexLinksOnLaunch() {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val launchCount = (preferences.getInt(KEY_LAUNCH_COUNT, 0) + 1).coerceAtMost(5)
        preferences.edit().putInt(KEY_LAUNCH_COUNT, launchCount).apply()

        if (launchCount == 3 || launchCount == 4) {
            showYandexLinksDialog = !canOpenYandexLinksByDefault()
        }
    }

    /** Проверяет, разрешена ли приложению обработка домена Яндекс Музыки. */
    private fun canOpenYandexLinksByDefault(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(DomainVerificationManager::class.java)
            val state = manager?.getDomainVerificationUserState(packageName) ?: return false
            val hostState = state.hostToStateMap[YANDEX_MUSIC_HOST]
            return state.isLinkHandlingAllowed &&
                (hostState == DomainVerificationUserState.DOMAIN_STATE_SELECTED ||
                    hostState == DomainVerificationUserState.DOMAIN_STATE_VERIFIED)
        }

        val linkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(YANDEX_MUSIC_LINK)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        return packageManager.resolveActivity(linkIntent, 0)?.activityInfo?.packageName == packageName
    }

    /** Открывает системные настройки обработки ссылок для этого приложения. */
    private fun openLinkSettings() {
        val packageUri = Uri.parse("package:$packageName")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                startActivity(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, packageUri))
                return
            } catch (_: ActivityNotFoundException) {
                // Некоторые прошивки не предоставляют отдельный экран ссылок.
            }
        }
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
    }

    companion object {
        private const val KEY_LAUNCH_COUNT = "app_launch_count"
        private const val YANDEX_MUSIC_HOST = "music.yandex.ru"
        private const val YANDEX_MUSIC_LINK = "https://music.yandex.ru/album/1"
        const val ACTION_OPEN_PLAYER =
            "com.yellastrodev.dwij.action.OPEN_PLAYER"
    }
}
