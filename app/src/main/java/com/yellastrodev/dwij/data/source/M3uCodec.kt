package com.yellastrodev.dwij.data.source

import com.yellastrodev.dwij.data.entities.LocalTrackEntity
import java.security.MessageDigest

/** Минимальный UTF-8 M3U/M3U8-кодек без зависимости от файловой системы. */
object M3uCodec {
    fun parse(text: String): List<String> = text
        .lineSequence()
        .map { line -> line.trim().trimStart('\uFEFF') }
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .toList()

    fun encode(tracks: List<LocalTrackEntity>): String = buildString {
        appendLine("#EXTM3U")
        tracks.forEach { track ->
            val durationSeconds = (track.durationMs / 1_000L).coerceAtLeast(0L)
            val label = listOfNotNull(track.artist, track.title)
                .filter(String::isNotBlank)
                .joinToString(" - ")
            appendLine("#EXTINF:$durationSeconds,$label")
            appendLine(track.absolutePath ?: track.contentUri)
        }
    }

    fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
