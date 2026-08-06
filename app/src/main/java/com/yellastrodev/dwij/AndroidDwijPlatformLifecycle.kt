package com.yellastrodev.dwij

import android.content.Context
import com.yellastrodev.dwij.data.repo.LocalMusicRepository
import com.yellastrodev.dwij.data.source.LocalLibraryMonitor
import com.yellastrodev.dwij.di.DwijPlatformLifecycle
import com.yellastrodev.dwij.work.LocalLibrarySyncWorker
import kotlinx.coroutines.CoroutineScope

/**
 * Android-наблюдение за MediaStore и планирование фоновой синхронизации.
 */
class AndroidDwijPlatformLifecycle(
    context: Context,
) : DwijPlatformLifecycle {

    private val context =
        context.applicationContext

    private var monitor:
        LocalLibraryMonitor? = null

    override fun startLocalLibraryIntegration(
        repository: LocalMusicRepository,
        scope: CoroutineScope,
    ) {
        if (monitor != null) {
            return
        }

        val newMonitor = LocalLibraryMonitor(
            repository = repository,
            scope = scope,
        )

        LocalLibraryMonitor
            .observedUris(context)
            .forEach { uri ->
                context.contentResolver
                    .registerContentObserver(
                        uri,
                        true,
                        newMonitor,
                    )
            }

        monitor = newMonitor

        LocalLibrarySyncWorker.schedule(context)

        if (repository.hasAudioPermission()) {
            LocalLibrarySyncWorker
                .enqueueImmediate(context)
        }
    }
}
