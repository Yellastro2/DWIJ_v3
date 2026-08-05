package com.yellastrodev.dwij.data.source

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntity
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntryEntity
import com.yellastrodev.dwij.data.entities.LocalPlaylistOrigin
import com.yellastrodev.dwij.data.entities.LocalTrackEntity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** Разрешение Android, необходимое для чтения аудио на текущей версии ОС. */
fun requiredAudioPermission(): String = if (Build.VERSION.SDK_INT >= 33) {
    Manifest.permission.READ_MEDIA_AUDIO
} else {
    Manifest.permission.READ_EXTERNAL_STORAGE
}

/** Набор разрешений Android для чтения и, на старых версиях, записи локальной медиатеки. */
fun requiredLocalMediaPermissions(): Array<String> = if (Build.VERSION.SDK_INT <= 28) {
    arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )
} else {
    arrayOf(requiredAudioPermission())
}

/** Проверяет доступ приложения к локальным аудиофайлам. */
fun hasAudioPermission(context: Context): Boolean = ContextCompat.checkSelfPermission(
    context,
    requiredAudioPermission(),
) == PackageManager.PERMISSION_GRANTED

/** Выполняет все медленные обращения к MediaStore и публичным M3U-файлам. */
class MediaStoreLocalSource(private val context: Context): LocalMediaSource {
    private val resolver: ContentResolver = context.contentResolver

    override fun scan(): LocalMediaSnapshot {
        val volumes = externalVolumeNames()
        val tracks = volumes.flatMap(::scanTracks).distinctBy(LocalTrackEntity::instanceId)
        val mediaStorePlaylists = scanLegacyPlaylists(tracks)
        val m3uPlaylists = scanM3uFiles(volumes, tracks)
        val playlists = mediaStorePlaylists.first + m3uPlaylists.first
        val entries = mediaStorePlaylists.second + m3uPlaylists.second
        return LocalMediaSnapshot(
            tracks = tracks,
            playlists = playlists.distinctBy(LocalPlaylistEntity::playlistId),
            entries = entries,
            generation = generationSignature(volumes),
        )
    }

    override fun currentGeneration(): String = generationSignature(externalVolumeNames())

    /**
     * Дешёво сверяет сохранённые MediaStore-атрибуты с файловой системой, не читая аудиотеги.
     * Это обнаруживает редакторы, которые меняют файл, но не уведомляют MediaStore.
     */
    override fun findChangedBackingFiles(tracks: List<LocalTrackEntity>): List<LocalTrackEntity> =
        tracks.filter { track ->
            val path = track.absolutePath ?: return@filter false
            val file = File(path)
            if (!file.isFile) return@filter false
            val modifiedSeconds = file.lastModified().takeIf { it > 0L }?.div(1_000L)
            val sizeBytes = file.length()
            modifiedSeconds != null && modifiedSeconds != track.dateModifiedSeconds ||
                track.sizeBytes != null && sizeBytes != track.sizeBytes
        }

    /**
     * Просит системный MediaScanner перечитать только изменившиеся файлы и ждёт все callback-и.
     * Таймаут не блокирует последующую обычную синхронизацию MediaStore.
     */
    override suspend fun rescanTracks(tracks: List<LocalTrackEntity>): Boolean {
        val paths = tracks.mapNotNull(LocalTrackEntity::absolutePath).distinct()
        if (paths.isEmpty()) return true
        val failedScans = AtomicInteger(0)
        val completed = withTimeoutOrNull(FILE_RESCAN_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val remaining = AtomicInteger(paths.size)
                MediaScannerConnection.scanFile(
                    context,
                    paths.toTypedArray(),
                    null,
                ) { _, uri ->
                    if (uri == null) failedScans.incrementAndGet()
                    if (remaining.decrementAndGet() == 0 && continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
            }
            true
        } ?: false
        Log.d(
            TAG,
            "[rescanTracks] Запрошено=${paths.size}, ошибок=${failedScans.get()}, " +
                "завершено=$completed",
        )
        return completed
    }

    private fun externalVolumeNames(): Set<String> = if (Build.VERSION.SDK_INT >= 29) {
        MediaStore.getExternalVolumeNames(context).ifEmpty {
            setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
    } else {
        setOf("external")
    }

    private fun scanTracks(volumeName: String): List<LocalTrackEntity> {
        val uri = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Audio.Media.getContentUri(volumeName)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.YEAR)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_MODIFIED)
            @Suppress("DEPRECATION")
            add(MediaStore.Audio.Media.DATA)
            if (Build.VERSION.SDK_INT >= 29) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
                add(MediaStore.Audio.Media.VOLUME_NAME)
            }
            if (Build.VERSION.SDK_INT >= 30) add(MediaStore.Audio.Media.DISC_NUMBER)
        }.toTypedArray()
        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
            append(" AND ${MediaStore.Audio.Media.DURATION} > 0")
            append(
                " AND (${MediaStore.Audio.Media.MIME_TYPE} LIKE ? OR " +
                    "${MediaStore.Audio.Media.MIME_TYPE} IN (?, ?))",
            )
            append(" AND ${MediaStore.Audio.Media.IS_RINGTONE} = 0")
            append(" AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0")
            append(" AND ${MediaStore.Audio.Media.IS_ALARM} = 0")
            if (Build.VERSION.SDK_INT >= 29) {
                append(" AND ${MediaStore.Audio.Media.IS_PENDING} = 0")
            }
        }
        val result = mutableListOf<LocalTrackEntity>()
        var skippedVideoFiles = 0
        val cursor = resolver.query(
            uri,
            projection,
            selection,
            ACCEPTED_AUDIO_MIME_SELECTION_ARGS,
            null,
        )
            ?: error("MediaStore вернул null-cursor для volume=$volumeName")
        cursor.use {
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(idColumn)
                val displayName = cursor.stringOrNull(MediaStore.Audio.Media.DISPLAY_NAME)
                    ?: "track-$mediaId"
                if (displayName.hasVideoExtension()) {
                    skippedVideoFiles++
                    continue
                }
                val actualVolume = cursor.stringOrNull(MediaStore.Audio.Media.VOLUME_NAME)
                    ?: volumeName
                val contentUri = ContentUris.withAppendedId(uri, mediaId).toString()
                result += LocalTrackEntity(
                    instanceId = "local:$actualVolume:$mediaId",
                    mediaStoreId = mediaId,
                    volumeName = actualVolume,
                    contentUri = contentUri,
                    displayName = displayName,
                    title = cursor.stringOrNull(MediaStore.Audio.Media.TITLE)
                        ?.takeIf(String::isNotBlank)
                        ?: cursor.stringOrNull(MediaStore.Audio.Media.DISPLAY_NAME)
                        ?: "Без названия",
                    artist = cursor.stringOrNull(MediaStore.Audio.Media.ARTIST)
                        ?.takeUnless { it == MediaStore.UNKNOWN_STRING },
                    album = cursor.stringOrNull(MediaStore.Audio.Media.ALBUM)
                        ?.takeUnless { it == MediaStore.UNKNOWN_STRING },
                    albumId = cursor.longOrNull(MediaStore.Audio.Media.ALBUM_ID),
                    durationMs = cursor.longOrNull(MediaStore.Audio.Media.DURATION) ?: 0L,
                    trackNumber = cursor.intOrNull(MediaStore.Audio.Media.TRACK),
                    discNumber = if (Build.VERSION.SDK_INT >= 30) {
                        cursor.intOrNull(MediaStore.Audio.Media.DISC_NUMBER)
                    } else null,
                    year = cursor.intOrNull(MediaStore.Audio.Media.YEAR),
                    mimeType = cursor.stringOrNull(MediaStore.Audio.Media.MIME_TYPE),
                    sizeBytes = cursor.longOrNull(MediaStore.Audio.Media.SIZE),
                    dateModifiedSeconds = cursor.longOrNull(MediaStore.Audio.Media.DATE_MODIFIED)
                        ?: 0L,
                    relativePath = cursor.stringOrNull(MediaStore.Audio.Media.RELATIVE_PATH),
                    absolutePath = cursor.stringOrNull(MediaStore.Audio.Media.DATA),
                )
            }
        }
        Log.d(
            TAG,
            "[scanTracks] volume=$volumeName, найдено=${result.size}, " +
                "отсеяноВидео=$skippedVideoFiles",
        )
        return result
    }

    @Suppress("DEPRECATION")
    private fun scanLegacyPlaylists(
        tracks: List<LocalTrackEntity>,
    ): Pair<List<LocalPlaylistEntity>, List<LocalPlaylistEntryEntity>> {
        val tracksByMediaId = tracks.groupBy(LocalTrackEntity::mediaStoreId)
        val playlists = mutableListOf<LocalPlaylistEntity>()
        val entries = mutableListOf<LocalPlaylistEntryEntity>()
        try {
            resolver.query(
                MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Audio.Playlists._ID,
                    MediaStore.Audio.Playlists.NAME,
                    MediaStore.Audio.Playlists.DATE_MODIFIED,
                ),
                null,
                null,
                MediaStore.Audio.Playlists.NAME,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Playlists._ID)
                while (cursor.moveToNext()) {
                    val externalId = cursor.getLong(idColumn)
                    val playlistId = "mediastore:$externalId"
                    playlists += LocalPlaylistEntity(
                        playlistId = playlistId,
                        name = cursor.stringOrNull(MediaStore.Audio.Playlists.NAME)
                            ?: "Плейлист $externalId",
                        origin = LocalPlaylistOrigin.MEDIA_STORE.name,
                        externalKey = externalId.toString(),
                        externalUri = ContentUris.withAppendedId(
                            MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                            externalId,
                        ).toString(),
                        dateModifiedSeconds = cursor.longOrNull(
                            MediaStore.Audio.Playlists.DATE_MODIFIED,
                        ) ?: 0L,
                        editable = false,
                    )
                    val membersUri = MediaStore.Audio.Playlists.Members.getContentUri(
                        "external",
                        externalId,
                    )
                    resolver.query(
                        membersUri,
                        arrayOf(
                            MediaStore.Audio.Playlists.Members.AUDIO_ID,
                            MediaStore.Audio.Playlists.Members.PLAY_ORDER,
                        ),
                        null,
                        null,
                        MediaStore.Audio.Playlists.Members.PLAY_ORDER,
                    )?.use { members ->
                        var fallbackPosition = 0
                        while (members.moveToNext()) {
                            val audioId = members.longOrNull(
                                MediaStore.Audio.Playlists.Members.AUDIO_ID,
                            )
                            val track = audioId?.let { tracksByMediaId[it]?.firstOrNull() }
                            val position = members.intOrNull(
                                MediaStore.Audio.Playlists.Members.PLAY_ORDER,
                            ) ?: fallbackPosition
                            entries += LocalPlaylistEntryEntity(
                                playlistId = playlistId,
                                position = position,
                                localTrackId = track?.instanceId,
                                rawReference = audioId?.toString(),
                            )
                            fallbackPosition++
                        }
                    }
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "[scanLegacyPlaylists] Системные плейлисты недоступны", error)
        }
        return playlists to entries
    }

    private fun scanM3uFiles(
        volumes: Set<String>,
        tracks: List<LocalTrackEntity>,
    ): Pair<List<LocalPlaylistEntity>, List<LocalPlaylistEntryEntity>> {
        val playlists = mutableListOf<LocalPlaylistEntity>()
        val entries = mutableListOf<LocalPlaylistEntryEntity>()
        volumes.forEach { volume ->
            val filesUri = MediaStore.Files.getContentUri(volume)
            val projection = buildList {
                add(MediaStore.Files.FileColumns._ID)
                add(MediaStore.Files.FileColumns.DISPLAY_NAME)
                add(MediaStore.Files.FileColumns.DATE_MODIFIED)
                @Suppress("DEPRECATION")
                add(MediaStore.Files.FileColumns.DATA)
            }.toTypedArray()
            try {
                resolver.query(
                    filesUri,
                    projection,
                    "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE ? OR " +
                        "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE ?",
                    arrayOf("%.m3u", "%.m3u8"),
                    null,
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    while (cursor.moveToNext()) {
                        val uri = ContentUris.withAppendedId(filesUri, cursor.getLong(idColumn))
                        val displayName = cursor.stringOrNull(MediaStore.Files.FileColumns.DISPLAY_NAME)
                            ?: continue
                        val absolutePath = cursor.stringOrNull(MediaStore.Files.FileColumns.DATA)
                        val rawText = try {
                            resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                                it.readText()
                            } ?: continue
                        } catch (error: Exception) {
                            Log.w(TAG, "[scanM3uFiles] Не удалось прочитать $uri", error)
                            continue
                        }
                        val playlistId = "m3u:${stableKey(uri.toString())}"
                        playlists += LocalPlaylistEntity(
                            playlistId = playlistId,
                            name = displayName.substringBeforeLast('.'),
                            origin = LocalPlaylistOrigin.M3U.name,
                            externalKey = uri.toString(),
                            externalUri = uri.toString(),
                            dateModifiedSeconds = cursor.longOrNull(
                                MediaStore.Files.FileColumns.DATE_MODIFIED,
                            ) ?: 0L,
                            editable = isDwijPlaylistPath(absolutePath),
                            exportedHash = if (isDwijPlaylistPath(absolutePath)) {
                                M3uCodec.hash(rawText)
                            } else null,
                        )
                        M3uCodec.parse(rawText).forEachIndexed { position, reference ->
                            entries += LocalPlaylistEntryEntity(
                                playlistId = playlistId,
                                position = position,
                                localTrackId = resolveTrack(reference, absolutePath, tracks)?.instanceId,
                                rawReference = reference,
                            )
                        }
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "[scanM3uFiles] Поиск M3U недоступен для volume=$volume", error)
            }
        }
        return playlists to entries
    }

    override fun exportM3u(
        name: String,
        tracks: List<LocalTrackEntity>,
        existingUri: String?,
    ): M3uExportResult {
        val text = M3uCodec.encode(tracks)
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "Плейлист" }
        val targetUri = if (Build.VERSION.SDK_INT >= 29) {
            val uri = existingUri?.let(Uri::parse) ?: resolver.insert(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "$safeName.m3u")
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-mpegurl")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, DWIJ_PLAYLIST_RELATIVE_PATH)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ) ?: error("MediaStore не создал M3U-файл")
            resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(text)
            } ?: error("Не удалось открыть M3U-файл для записи")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            uri
        } else {
            @Suppress("DEPRECATION")
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "DWIJ/Playlists",
            ).apply { mkdirs() }
            val file = File(directory, "$safeName.m3u")
            file.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("audio/x-mpegurl"),
                null,
            )
            Uri.fromFile(file)
        }
        Log.d(TAG, "[exportM3u] Экспортирован плейлист '$name': tracks=${tracks.size}")
        return M3uExportResult(targetUri.toString(), M3uCodec.hash(text))
    }

    private fun generationSignature(volumes: Set<String>): String = buildString {
        append("filter:")
        append(LOCAL_TRACK_FILTER_VERSION)
        volumes.sorted().forEach { volume ->
            val generation = if (Build.VERSION.SDK_INT >= 30) {
                runCatching { MediaStore.getGeneration(context, volume) }.getOrDefault(-1L)
            } else {
                -1L
            }
            append('|')
            append(volume)
            append(':')
            append(generation)
        }
    }

    private fun resolveTrack(
        reference: String,
        playlistAbsolutePath: String?,
        tracks: List<LocalTrackEntity>,
    ): LocalTrackEntity? {
        tracks.firstOrNull { it.contentUri == reference }?.let { return it }
        val normalized = normalizePath(reference)
        tracks.firstOrNull { normalizePath(it.absolutePath) == normalized }?.let { return it }
        if (playlistAbsolutePath != null && !File(reference).isAbsolute) {
            val resolved = runCatching {
                File(File(playlistAbsolutePath).parentFile, reference).canonicalPath
            }.getOrNull()
            tracks.firstOrNull { normalizePath(it.absolutePath) == normalizePath(resolved) }
                ?.let { return it }
        }
        val filenameMatches = tracks.filter {
            it.displayName.equals(File(reference).name, ignoreCase = true)
        }
        return filenameMatches.singleOrNull()
    }

    private fun normalizePath(value: String?): String? = value
        ?.replace('\\', '/')
        ?.trim()
        ?.lowercase()

    /** Защита от прошивок, которые ошибочно добавляют MP4/MKV в Audio.Media. */
    private fun String.hasVideoExtension(): Boolean =
        substringAfterLast('.', missingDelimiterValue = "").lowercase() in VIDEO_EXTENSIONS

    private fun stableKey(value: String): String = MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun isDwijPlaylistPath(path: String?): Boolean = normalizePath(path)
        ?.contains("/music/dwij/playlists/") == true

    private fun android.database.Cursor.stringOrNull(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

    private fun android.database.Cursor.longOrNull(column: String): Long? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)

    private fun android.database.Cursor.intOrNull(column: String): Int? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getInt)

    companion object {
        private const val TAG = "MediaStoreLocalSource"
        private const val DWIJ_PLAYLIST_RELATIVE_PATH = "Music/DWIJ/Playlists/"
        /** Изменяется, когда критерий того, что считать музыкальным файлом, становится строже. */
        private const val LOCAL_TRACK_FILTER_VERSION = 2
        private val ACCEPTED_AUDIO_MIME_SELECTION_ARGS = arrayOf(
            "audio/%",
            "application/ogg",
            "application/x-ogg",
        )
        private val VIDEO_EXTENSIONS = setOf(
            "3gp",
            "3g2",
            "avi",
            "mkv",
            "mov",
            "mp4",
            "mpeg",
            "mpg",
            "m4v",
            "ts",
            "webm",
        )
        private const val FILE_RESCAN_TIMEOUT_MS = 120_000L
    }
}
