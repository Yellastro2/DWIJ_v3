package com.yellastrodev.dwij.playback

/** Срок подготовки и затыка не продлевается повторными BUFFERING или повторами сетевых запросов. */
internal class AndroidPlaybackAttempt(val startedMs: Long) {
    var failed = false
        private set
    private var ready = false
    private var stalledSinceMs: Long? = null

    /** READY завершает подготовку или текущий затык; следующий затык получает новый срок. */
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
        const val PREPARE_TIMEOUT_MS = 20_000L
        const val STALL_TIMEOUT_MS = 20_000L
    }
}
