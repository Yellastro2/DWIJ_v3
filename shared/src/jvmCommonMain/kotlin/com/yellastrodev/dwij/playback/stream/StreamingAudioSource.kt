package com.yellastrodev.dwij.playback.stream

import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.yamusicsdk.download.AudioRange

/** Платформонезависимый источник аудиобайтов. Ссылку фиксируем на время одной сессии. */
interface StreamingAudioSource {
    suspend fun resolveUrl(trackId: String): DataResult<String>
    suspend fun readRange(
        url: String, start: Long, length: Long,
        onHeaders: (AudioRange) -> Unit,
        onBytes: (Long, ByteArray, Int) -> Unit,
    ): DataResult<Unit>
}
