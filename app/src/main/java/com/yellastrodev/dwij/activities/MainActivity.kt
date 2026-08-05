package com.yellastrodev.dwij.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.navigation.DwijApp
import com.yellastrodev.dwij.yApplication

/** Единственная Activity приложения; всё содержимое и навигация размещены в Compose. */
class MainActivity : ComponentActivity() {
    val playerModel: PlayerModel by viewModels {
        val application = application as yApplication
        PlayerModel.Factory(
            playerRepo = application.playerRepo,
            coverRepo = application.coverRepository,
            playlistRepo = application.playlistRepository,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContent {
            DwijApp(playerModel = playerModel)
        }
    }
}
