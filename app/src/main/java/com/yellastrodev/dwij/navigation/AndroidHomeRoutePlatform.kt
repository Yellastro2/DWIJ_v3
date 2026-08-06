package com.yellastrodev.dwij.navigation

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.yellastrodev.dwij.data.source.requiredLocalMediaPermissions
import com.yellastrodev.dwij.work.LocalLibrarySyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/** Android-реализация разрешений и синхронизации домашнего маршрута. */
@Composable
fun rememberAndroidHomeRoutePlatform(): HomeRoutePlatform {
    val context = LocalContext.current

    var pendingPermissionRequest by remember {
        mutableStateOf<CompletableDeferred<Boolean>?>(null)
    }

    fun checkLocalMusicAccess(): Boolean =
        requiredLocalMediaPermissions().all { permission ->
            ContextCompat.checkSelfPermission(
                context,
                permission,
            ) == PackageManager.PERMISSION_GRANTED
        }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val granted = requiredLocalMediaPermissions().all { permission ->
            permissions[permission] == true ||
                ContextCompat.checkSelfPermission(
                    context,
                    permission,
                ) == PackageManager.PERMISSION_GRANTED
        }

        pendingPermissionRequest?.complete(granted)
        pendingPermissionRequest = null
    }

    DisposableEffect(Unit) {
        onDispose {
            pendingPermissionRequest?.cancel()
            pendingPermissionRequest = null
        }
    }

    return remember(context, permissionLauncher) {
        object : HomeRoutePlatform {
            override fun hasLocalMusicAccess(): Boolean =
                checkLocalMusicAccess()

            override suspend fun requestLocalMusicAccess(): Boolean {
                if (checkLocalMusicAccess()) {
                    return true
                }

                pendingPermissionRequest?.let { request ->
                    return request.await()
                }

                val request = CompletableDeferred<Boolean>()
                pendingPermissionRequest = request
                permissionLauncher.launch(
                    requiredLocalMediaPermissions(),
                )

                return try {
                    request.await()
                } catch (error: CancellationException) {
                    if (pendingPermissionRequest === request) {
                        pendingPermissionRequest = null
                    }

                    request.cancel()
                    throw error
                }
            }

            override fun startLocalLibrarySync() {
                LocalLibrarySyncWorker.enqueueImmediate(
                    context.applicationContext,
                )
            }
        }
    }
}
