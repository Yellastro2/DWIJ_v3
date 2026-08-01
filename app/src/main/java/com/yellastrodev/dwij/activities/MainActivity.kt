package com.yellastrodev.dwij.activities

import android.Manifest
import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.R.id.message
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.View.OnClickListener
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.snackbar.Snackbar
import com.yellastrodev.dwij.HomeCompactPlayer
import com.yellastrodev.dwij.HomeCompactPlayerUiState
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.models.PlayerModel
import com.yellastrodev.dwij.yApplication
import com.yellastrodev.yandexmusiclib.entities.CoverSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.logging.Logger
import kotlin.getValue


class MainActivity : AppCompatActivity() {

    companion object {
        val FIRST_TRACKLIST = "tracklist"
        val FIRST_HOME = "home"
        val FIRST_PLAYER = "player"
        val FIRST_PLLIST = "playlist"
        val FIRST_TYPES = listOf<String>(FIRST_PLAYER, FIRST_PLLIST, FIRST_TRACKLIST)

        val LOG = Logger.getLogger("MainActivity")
        val RECORD_REQUEST_CODE = 31437

    }


    lateinit var mNavController: NavController

    val playerRepo: PlayerRepository by lazy {
        (application as yApplication).playerRepo
    }

    val playerModel: PlayerModel by viewModels {
        PlayerModel.Factory(
            playerRepo = (application as yApplication).playerRepo,
            trackRepo = (application as yApplication).trackRepository,
            coverRepo = (application as yApplication).coverRepository,
            playlistRepo = (application as yApplication).playlistRepository
        )
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.lay_main)
        applySystemBarInsets(
            rootView = findViewById(R.id.main_lay),
            useDarkSystemBarIcons = false,
        )
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragmentContainerView) as NavHostFragment
        mNavController = navHostFragment.navController

        findViewById<ComposeView>(R.id.main_compact_player).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val track by playerModel.track.collectAsState()
                val playerState by playerModel.playerState.collectAsState()
                var cover by remember(track?.id) {
                    mutableStateOf<ImageBitmap?>(null)
                }
                val unknownArtist = stringResource(R.string.home_player_unknown_artist)

                LaunchedEffect(track?.id) {
                    track?.let { currentTrack ->
                        playerModel.coverRepo
                            .getCoverFlow(currentTrack, CoverSize.`100x100`)
                            .flowOn(Dispatchers.IO)
                            .collect { bitmap ->
                                cover = bitmap.asImageBitmap()
                            }
                    }
                }

                track?.let { currentTrack ->
                    HomeCompactPlayer(
                        player = HomeCompactPlayerUiState(
                            title = currentTrack.title,
                            artist = currentTrack.artists
                                .joinToString(", ") { artist -> artist.name }
                                .ifBlank { unknownArtist },
                            cover = cover,
                            isPlaying = playerState.isPlaying,
                            currentPositionMillis = playerState.currentPosition,
                            durationMillis = playerState.duration,
                        ),
                        onOpenClick = {
                            mNavController.navigate(R.id.bigPlayerFrag)
                        },
                        onPlayPauseClick = playerModel::playAudio,
                        onNextClick = {
                            lifecycleScope.launch {
                                playerModel.nextTrack()
                            }
                        },
                    )
                }
            }
        }

        mNavController.addOnDestinationChangedListener { _, destination, _ ->
            updateCompactPlayerVisibility(destination.id)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playerRepo.currentTrack.collect {
                    updateCompactPlayerVisibility(mNavController.currentDestination?.id)
                }
            }
        }

//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
//                != PackageManager.PERMISSION_GRANTED
//            ) {
//                ActivityCompat.requestPermissions(
//                    this,
//                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
//                    1001
//                )
//            }
//        }

    }

    /** Показывает Compose-мини-плеер на прежнем месте, кроме главной и полного плеера. */
    private fun updateCompactPlayerVisibility(currentDestinationId: Int?) {
        val hasCurrentTrack = playerRepo.currentTrack.value != null
        val isPlayerHiddenDestination = currentDestinationId == R.id.homeFrag ||
            currentDestinationId == R.id.bigPlayerFrag
        findViewById<View>(R.id.main_compact_player).isVisible =
            hasCurrentTrack && !isPlayerHiddenDestination
    }

}
