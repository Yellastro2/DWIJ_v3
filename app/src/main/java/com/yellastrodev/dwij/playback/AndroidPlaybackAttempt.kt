package com.yellastrodev.dwij.playback

/** Срок одной попытки не продлевается повторными BUFFERING и заменой метаданных. */
internal class AndroidPlaybackAttempt(val startedMs: Long, val retry: Int = 0) {
    var failed = false
        private set
    private var ready = false
    private var stalledSinceMs: Long? = null
    val canRetry: Boolean get() = retry < MAX_RETRIES

    /** READY завершает подготовку, но сохраняет число уже потраченных повторов трека. */
    fun onReady() {
        ready = true
        stalledSinceMs = null
    }

    /** Пауза не считается зависанием; повторные уведомления не сдвигают начало ожидания. */
    fun onBuffering(nowMs: Long, wantsToPlay: Boolean) {
        if (!wantsToPlay) stalledSinceMs = null
        else if (ready && stalledSinceMs == null) stalledSinceMs = nowMs
    }

    /** Возвращает общий дедлайн подготовки или дедлайн зависания после READY. */
    fun deadlineMs(): Long? = when {
        failed -> null
        !ready -> startedMs + PREPARE_TIMEOUT_MS
        else -> stalledSinceMs?.plus(STALL_TIMEOUT_MS)
    }

    /** Объединяет ошибку Media3 и таймаут одной попытки в один отказ. */
    fun fail(): Boolean {
        if (failed) return false
        failed = true
        return true
    }

    companion object {
        const val MAX_RETRIES = 3
        const val PREPARE_TIMEOUT_MS = 12_000L
        const val STALL_TIMEOUT_MS = 5_000L
        const val RETRY_DELAY_MS = 250L
    }
}
