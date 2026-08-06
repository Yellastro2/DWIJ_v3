package com.yellastrodev.dwij.activities

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

/**
 * Android-точка входа приложения.
 *
 * Создаёт Android-зависимости и запускает полностью shared Compose-корень.
 */
class MainActivity : ComponentActivity() {

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
                )
            }
        }
    }
}
