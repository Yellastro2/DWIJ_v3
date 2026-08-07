package com.yellastrodev.dwij.desktop

import com.yellastrodev.dwij.data.repo.LocalMusicRepository
import com.yellastrodev.dwij.di.DwijPlatformLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Desktop lifecycle: при старте один раз индексирует файловую медиатеку.
 *
 * Постоянный FileSystem watcher намеренно оставлен на следующий проход.
 */
object DesktopDwijPlatformLifecycle :
    DwijPlatformLifecycle {

    override fun startLocalLibraryIntegration(
        repository: LocalMusicRepository,
        scope: CoroutineScope,
    ) {
        scope.launch {
            repository.synchronize(
                force =
                    false,
            )
        }
    }
}
