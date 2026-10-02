package com.yellastrodev.dwij.desktop.playback

import java.util.concurrent.atomic.AtomicBoolean

/** Одна попытка запуска; VK получает бюджет для цепочки API/HLS, конкурентные отказы дают один повтор. */
internal class DesktopPlaybackAttempt(val index: Int, val retry: Int = 0) {
    private val failed = AtomicBoolean()
    val isFailed: Boolean get() = failed.get()
    val canRetry: Boolean get() = retry < MAX_RETRIES

    /** Принимает только первый сигнал отказа этой попытки. */
    fun fail(): Boolean = failed.compareAndSet(false, true)

    companion object {
        const val MAX_RETRIES = 3
        const val PREPARE_TIMEOUT_MS = 12_000L
        const val VK_PREPARE_TIMEOUT_MS = 45_000L
        const val STALL_TIMEOUT_MS = 5_000L
        const val VK_STALL_TIMEOUT_MS = 20_000L
        const val RETRY_DELAY_MS = 250L
    }
}
