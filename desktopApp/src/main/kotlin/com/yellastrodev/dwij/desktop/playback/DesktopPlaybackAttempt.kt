package com.yellastrodev.dwij.desktop.playback

import java.util.concurrent.atomic.AtomicBoolean

/** Одна попытка запуска; конкурентные ошибки сети, JavaFX и watchdog дают только один повтор. */
internal class DesktopPlaybackAttempt(val index: Int, val retry: Int = 0) {
    private val failed = AtomicBoolean()
    val isFailed: Boolean get() = failed.get()
    val canRetry: Boolean get() = retry < MAX_RETRIES

    /** Принимает только первый сигнал отказа этой попытки. */
    fun fail(): Boolean = failed.compareAndSet(false, true)

    companion object {
        const val MAX_RETRIES = 3
        const val PREPARE_TIMEOUT_MS = 12_000L
        const val STALL_TIMEOUT_MS = 5_000L
        const val RETRY_DELAY_MS = 250L
    }
}
