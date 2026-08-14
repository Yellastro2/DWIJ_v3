package com.yellastrodev.dwij.data.source

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.repo.LocalMusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Дебаунсит уведомления MediaStore и обновляет Room одним сканированием. */
class LocalLibraryMonitor(
    private val repository: LocalMusicRepository,
    private val scope: CoroutineScope,
    private val onSynchronized: () -> Unit,
) : ContentObserver(Handler(Looper.getMainLooper())) {
    private var pendingSync: Job? = null

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        pendingSync?.cancel()
        pendingSync = scope.launch {
            delay(DEBOUNCE_MS)
            Log.d(TAG, "[onChange] MediaStore изменился: uri=$uri")
            if (repository.synchronize(force = true) is DataResult.Success) {
                onSynchronized()
            }
        }
    }

    companion object {
        private const val TAG = "LocalLibraryMonitor"
        private const val DEBOUNCE_MS = 2_000L

        /** Наблюдает как общий external URI, так и реальные тома вроде external_primary. */
        fun observedUris(context: Context): List<Uri> =
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val volumes = buildSet {
                    add("external")
                    addAll(
                        runCatching { MediaStore.getExternalVolumeNames(context) }
                            .getOrDefault(emptySet())
                    )
                }
                volumes.flatMap { volume ->
                    listOf(
                        MediaStore.Audio.Media.getContentUri(volume),
                        MediaStore.Files.getContentUri(volume),
                    )
                }.distinct()
            } else {
                listOf(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    MediaStore.Files.getContentUri("external"),
                )
            }
    }
}
