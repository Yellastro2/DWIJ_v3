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
import com.yellastrodev.dwij.navigation.DwijApp
import com.yellastrodev.dwij.ui.LocalYamLogger
import com.yellastrodev.dwij.yApplication

/**
 * Единственная Activity приложения.
 *
 * Всё содержимое и навигация размещены в Compose.
 */
class MainActivity : ComponentActivity() {

    val playerModel: PlayerModel by viewModels {
        viewModelFactory {
            initializer {
                val application =
                    this@MainActivity.application as yApplication

                PlayerModel(
                    playerRepo =
                        application.playerRepo,
                    playlistRepo =
                        application.playlistRepository,
                    coverLoader =
                        AndroidPlayerCoverLoader(
                            context =
                                application.applicationContext,
                            coverRepository =
                                application.coverRepository,
                        ),
                )
            }
        }
    }

    override fun onCreate(
        savedInstanceState: Bundle?,
    ) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        WindowCompat
            .getInsetsController(
                window,
                window.decorView,
            )
            .apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
        }

        setContent {
            val dwijApplication =
                this@MainActivity.application as yApplication
            CompositionLocalProvider(
                LocalYamLogger provides dwijApplication.logger,
            ) {
                DwijApp(
                    playerModel = playerModel,
                )
            }
        }
    }
}
