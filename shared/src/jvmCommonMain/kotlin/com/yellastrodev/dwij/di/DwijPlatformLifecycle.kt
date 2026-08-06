package com.yellastrodev.dwij.di

import com.yellastrodev.dwij.data.repo.LocalMusicRepository
import kotlinx.coroutines.CoroutineScope

/**
 * Платформенный запуск наблюдения и фоновой синхронизации медиатеки.
 */
interface DwijPlatformLifecycle {
    fun startLocalLibraryIntegration(
        repository: LocalMusicRepository,
        scope: CoroutineScope,
    )
}

/** Пустая реализация для платформ без системной медиатеки. */
object NoOpDwijPlatformLifecycle : DwijPlatformLifecycle {
    override fun startLocalLibraryIntegration(
        repository: LocalMusicRepository,
        scope: CoroutineScope,
    ) = Unit
}
