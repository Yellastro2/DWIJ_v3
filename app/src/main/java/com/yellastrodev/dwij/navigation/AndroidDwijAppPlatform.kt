package com.yellastrodev.dwij.navigation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.yellastrodev.dwij.AndroidHomeScreenPlatform
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.service.LocalTrackDownloadService
import com.yellastrodev.dwij.ui.HomeScreenPlatform
import com.yellastrodev.dwij.ui.playlist.PlaylistGridPlatform

/** Android-набор системных реализаций для shared [DwijApp]. */
object AndroidDwijAppPlatform : DwijAppPlatform {
    override val homeScreenPlatform: HomeScreenPlatform =
        AndroidHomeScreenPlatform

    @Composable
    override fun rememberHomeRoutePlatform(): HomeRoutePlatform =
        rememberAndroidHomeRoutePlatform()

    @Composable
    override fun rememberPlaylistGridPlatform(): PlaylistGridPlatform =
        rememberAndroidPlaylistGridPlatform()

    @Composable
    override fun rememberLocalLibraryPlatform(): LocalLibraryPlatform =
        rememberAndroidLocalLibraryPlatform()

    @Composable
    override fun rememberSettingsPlatform(): SettingsPlatform =
        rememberAndroidSettingsPlatform()

    @Composable
    override fun rememberLocalTrackDownloadRequester(): LocalTrackDownloadRequester {
        val context = LocalContext.current
        var pendingRequest by remember {
            mutableStateOf<Pair<String, String>?>(null)
        }
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) {
            pendingRequest?.let { (trackId, title) ->
                runCatching {
                    LocalTrackDownloadService.enqueue(context, trackId, title)
                }.onFailure { error ->
                    Log.e(
                        LOCAL_TRACK_DOWNLOAD_TAG,
                        "[request] Не удалось запустить foreground service",
                        error,
                    )
                }
            }
            pendingRequest = null
        }

        return LocalTrackDownloadRequester { trackId, title ->
            val hasNotificationPermission =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED

            if (hasNotificationPermission) {
                runCatching {
                    LocalTrackDownloadService.enqueue(context, trackId, title)
                }.onFailure { error ->
                    Log.e(
                        LOCAL_TRACK_DOWNLOAD_TAG,
                        "[request] Не удалось запустить foreground service",
                        error,
                    )
                }
            } else {
                pendingRequest = trackId to title
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    @Composable
    override fun rememberShareRequester(): ShareRequester {
        val context = LocalContext.current
        return remember(context) {
            ShareRequester { url ->
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, url)
                }
                context.startActivity(
                    Intent.createChooser(
                        sendIntent,
                        context.getString(R.string.object_share),
                    ),
                )
            }
        }
    }
}

private const val LOCAL_TRACK_DOWNLOAD_TAG = "LocalTrackDownload"
