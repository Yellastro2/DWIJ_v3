package com.yellastrodev.dwij.navigation

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.work.LocalLibrarySyncWorker

/** Android-реализация lifecycle, WorkManager, Toast и навигации медиатеки. */
@Composable
fun rememberAndroidLocalLibraryPlatform(
    navController: NavHostController,
): LocalLibraryPlatform {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val platform = remember(context, navController) {
        object : LocalLibraryPlatform {
            override fun startLocalLibrarySync() {
                LocalLibrarySyncWorker.enqueueImmediate(
                    context.applicationContext,
                )
            }

            override fun openPlayer() {
                navController.navigate(DwijDestination.PLAYER)
            }

            override fun openPlaylist(playlistId: String) {
                navController.navigate(
                    DwijDestination.localLibraryRoute(
                        mode = DwijDestination.LOCAL_MODE_PLAYLIST,
                        playlistId = playlistId,
                    ),
                )
            }

            override fun closeScreen() {
                navController.navigateUp()
            }

            override fun showTrackHideFailed() {
                Toast.makeText(
                    context,
                    R.string.local_track_hide_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    DisposableEffect(lifecycleOwner, platform) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                platform.startLocalLibrarySync()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    return platform
}
