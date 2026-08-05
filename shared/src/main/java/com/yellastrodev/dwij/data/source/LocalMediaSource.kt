package com.yellastrodev.dwij.data.source

import com.yellastrodev.dwij.data.entities.LocalTrackEntity
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntity
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntryEntity

/** Независимый от платформы снимок локальной медиатеки для сохранения в Room. */
data class LocalMediaSnapshot(
    val tracks: List<LocalTrackEntity>,
    val playlists: List<LocalPlaylistEntity>,
    val entries: List<LocalPlaylistEntryEntity>,
    val generation: String,
)

/** Результат платформенного сохранения публичного M3U-файла. */
data class M3uExportResult(
    val uri: String,
    val hash: String,
)

/** Платформенный источник локальных треков и публичных M3U-плейлистов. */
interface LocalMediaSource {
    fun currentGeneration(): String

    fun findChangedBackingFiles(
        tracks: List<LocalTrackEntity>,
    ): List<LocalTrackEntity>

    suspend fun rescanTracks(
        tracks: List<LocalTrackEntity>,
    ): Boolean

    fun scan(): LocalMediaSnapshot

    fun exportM3u(
        name: String,
        tracks: List<LocalTrackEntity>,
        existingUri: String?,
    ): M3uExportResult
}
