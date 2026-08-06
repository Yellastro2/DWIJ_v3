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
import androidx.navigation.NavHostController
import com.yellastrodev.dwij.data.source.requiredLocalMediaPermissions
import com.yellastrodev.dwij.ui.playlist.PlaylistGridPlatform
import com.yellastrodev.dwij.work.LocalLibrarySyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

@Composable
fun rememberAndroidPlaylistGridPlatform(
    navController: NavHostController,
): PlaylistGridPlatform {
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

    return remember(context, navController, permissionLauncher) {
        object : PlaylistGridPlatform {
            override fun hasLocalMusicAccess(): Boolean =
                checkLocalMusicAccess()

            override suspend fun requestLocalMusicAccess(): Boolean {
                if (checkLocalMusicAccess()) return true

                pendingPermissionRequest?.let { currentRequest ->
                    return currentRequest.await()
                }

                val request = CompletableDeferred<Boolean>()
                pendingPermissionRequest = request
                permissionLauncher.launch(requiredLocalMediaPermissions())

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

            override fun openYandexPlaylist(playlistId: String) {
                navController.navigate(
                    DwijDestination.objectRoute(
                        type = DwijDestination.OBJECT_TYPE_PLAYLIST,
                        value = playlistId,
                    ),
                )
            }

            override fun openLocalPlaylist(playlistId: String) {
                navController.navigate(
                    DwijDestination.localLibraryRoute(
                        mode = DwijDestination.LOCAL_MODE_PLAYLIST,
                        playlistId = playlistId,
                    ),
                )
            }

            override fun closeScreen() {
                navController.popBackStack()
            }
        }
    }
}