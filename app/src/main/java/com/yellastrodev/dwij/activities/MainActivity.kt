package com.yellastrodev.dwij.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.yellastrodev.dwij.models.AndroidPlayerCoverLoader
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.navigation.AndroidDwijAppPlatform
import com.yellastrodev.dwij.navigation.DwijApp
import com.yellastrodev.dwij.ui.LocalYamLogger
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Android-точка входа приложения.
 *
 * Создаёт Android-зависимости и запускает полностью shared Compose-корень.
 */
class MainActivity : ComponentActivity() {

    private val playerOpenRequests = Channel<Unit>(Channel.CONFLATED)
    private val playerOpenRequestFlow = playerOpenRequests.receiveAsFlow()

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

        consumePlayerOpenIntent(intent)

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
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumePlayerOpenIntent(intent)
    }

    /** Передаёт одноразовую команду системного медиаплеера в Compose-навигацию. */
    private fun consumePlayerOpenIntent(intent: Intent?) {
        if (intent?.action != ACTION_OPEN_PLAYER) return

        playerOpenRequests.trySend(Unit)
        intent.action = Intent.ACTION_MAIN
    }

    companion object {
        const val ACTION_OPEN_PLAYER =
            "com.yellastrodev.dwij.action.OPEN_PLAYER"
    }
}
