package com.yellastrodev.dwij.navigation

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.work.LocalLibrarySyncWorker

/** Android lifecycle, WorkManager и сообщения локальной медиатеки. */
@Composable
fun rememberAndroidLocalLibraryPlatform(): LocalLibraryPlatform {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val platform = remember(context) {
        object : LocalLibraryPlatform {
            override fun startLocalLibrarySync() {
                LocalLibrarySyncWorker.enqueueImmediate(
                    context.applicationContext,
                )
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
