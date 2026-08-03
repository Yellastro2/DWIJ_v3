package com.yellastrodev.dwij.data.repo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.yellastrodev.dwij.CacheManager
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.dao.LocalLibraryDao
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntity
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntryEntity
import com.yellastrodev.dwij.data.entities.LocalPlaylistOrigin
import com.yellastrodev.dwij.data.entities.LocalTrackEntity
import com.yellastrodev.dwij.data.entities.LocalTracklist
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.source.MediaStoreLocalSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class LocalSyncSummary(
    val tracks: Int,
    val playlists: Int,
    val skipped: Boolean,
)

/** Room-backed локальная медиатека; MediaStore используется только для синхронизации. */
class LocalMusicRepository(
    private val context: Context,
    private val dao: LocalLibraryDao,
    private val mediaStore: MediaStoreLocalSource,
    private val songRepository: SongRepository,
    private val cacheManager: CacheManager,
    private val database: RoomDatabase,
) {
    private val syncMutex = Mutex()
    private val _isSynchronizing = MutableStateFlow(false)
    /** Истина только во время фактического чтения MediaStore и применения снимка. */
    val isSynchronizing: StateFlow<Boolean> = _isSynchronizing
    private val coverCacheDirectory = File(context.cacheDir, LOCAL_COVER_CACHE_DIR).apply {
        if (!exists()) mkdirs()
    }
    private val coverMemoryCache = object : LruCache<String, Bitmap>(COVER_MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val coverLocks = ConcurrentHashMap<String, Mutex>()
    private val coverWrites = AtomicInteger()

    init {
        cacheManager.registerDir(coverCacheDirectory)
    }

    val tracks: Flow<List<LocalTrackEntity>> = dao.observeAllTracks()
    val songs: Flow<List<Song>> = songRepository.localSongs
    val playlists: Flow<List<LocalPlaylistEntity>> = dao.observePlaylists()

    fun playlist(playlistId: String): Flow<LocalPlaylistEntity?> =
        dao.observePlaylist(playlistId)

    fun playlistTracks(playlistId: String): Flow<List<LocalTrackEntity>> =
        dao.observePlaylistTracks(playlistId)

    fun playlistSongs(playlistId: String): Flow<List<Song>> =
        playlistTracks(playlistId).map { sourceTracks ->
            songRepository.songsForLocalTracks(sourceTracks)
        }

    fun hasAudioPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        requiredAudioPermission(),
    ) == PackageManager.PERMISSION_GRANTED

    suspend fun synchronize(force: Boolean): DataResult<LocalSyncSummary> = syncMutex.withLock {
        if (!hasAudioPermission()) return DataResult.Failure(DataError.Unauthorized)
        _isSynchronizing.value = true
        try {
            val currentGeneration = mediaStore.currentGeneration()
            val savedGeneration = dao.getState(LocalLibraryDao.LAST_GENERATION_KEY)
            if (!force && currentGeneration == savedGeneration && !currentGeneration.contains(":-1")) {
                Log.d(TAG, "[synchronize] MediaStore не изменился, сканирование пропущено")
                return DataResult.Success(LocalSyncSummary(0, 0, skipped = true))
            }
            val snapshot = mediaStore.scan()
            val storedTracks = dao.getAllTracks().associateBy(LocalTrackEntity::instanceId)
            val scannedTracks = snapshot.tracks.associateBy(LocalTrackEntity::instanceId)
            val changedTracks = snapshot.tracks.filter { track ->
                storedTracks[track.instanceId] != track
            }
            val removedTrackIds = storedTracks.keys.minus(scannedTracks.keys).toList()
            val dwijExternalUris = dao.getPlaylistsByOrigin()
                .mapNotNull(LocalPlaylistEntity::externalUri)
                .toSet()
            val importedPlaylists = snapshot.playlists.filterNot {
                it.origin == LocalPlaylistOrigin.M3U.name && it.externalUri in dwijExternalUris
            }
            val importedIds = importedPlaylists.map(LocalPlaylistEntity::playlistId).toSet()
            val tracksByUri = snapshot.tracks.associateBy(LocalTrackEntity::contentUri)
            val tracksByPath = snapshot.tracks
                .mapNotNull { track -> track.absolutePath?.let { it.normalizedPath() to track } }
                .toMap()
            database.withTransaction {
                dao.applyMediaSnapshotDiff(
                    tracksToUpsert = changedTracks,
                    trackIdsToDelete = removedTrackIds,
                    playlists = importedPlaylists,
                    entries = snapshot.entries.filter { it.playlistId in importedIds },
                    generation = snapshot.generation,
                )
                songRepository.registerLocalTracks(changedTracks)
                songRepository.removeLocalTracks(removedTrackIds)
                val resolvedDwijEntries = dao.getUnresolvedDwijEntries().mapNotNull { entry ->
                    val reference = entry.rawReference ?: return@mapNotNull null
                    val track = tracksByUri[reference] ?: tracksByPath[reference.normalizedPath()]
                    track?.let { entry.copy(localTrackId = it.instanceId) }
                }
                if (resolvedDwijEntries.isNotEmpty()) {
                    dao.upsertPlaylistEntries(resolvedDwijEntries)
                }
            }
            Log.d(
                TAG,
                "[synchronize] Всего tracks=${snapshot.tracks.size}, " +
                    "изменено=${changedTracks.size}, удалено=${removedTrackIds.size}, " +
                    "playlists=${importedPlaylists.size}",
            )
            DataResult.Success(
                LocalSyncSummary(
                    tracks = snapshot.tracks.size,
                    playlists = importedPlaylists.size,
                    skipped = false,
                )
            )
        } catch (error: SecurityException) {
            Log.w(TAG, "[synchronize] Нет доступа к MediaStore", error)
            DataResult.Failure(DataError.Unauthorized)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "[synchronize] Сканирование MediaStore завершилось ошибкой", error)
            DataResult.Failure(DataError.Storage(error))
        } finally {
            _isSynchronizing.value = false
        }
    }

    /** Создаёт или полностью обновляет плейлист Движа и его публичный M3U. */
    suspend fun saveDwijPlaylist(
        playlistId: String = "dwij:${UUID.randomUUID()}",
        name: String,
        trackIds: List<String>,
    ): DataResult<LocalPlaylistEntity> = try {
        val existing = dao.getPlaylist(playlistId)
        val trackMap = dao.getTracks(trackIds).associateBy(LocalTrackEntity::instanceId)
        val orderedTracks = trackIds.mapNotNull(trackMap::get)
        val export = mediaStore.exportM3u(name, orderedTracks, existing?.externalUri)
        val playlist = LocalPlaylistEntity(
            playlistId = playlistId,
            name = name.trim().ifBlank { "Плейлист" },
            origin = LocalPlaylistOrigin.DWIJ.name,
            externalKey = playlistId,
            externalUri = export.uri,
            dateModifiedSeconds = System.currentTimeMillis() / 1_000L,
            editable = true,
            exportedHash = export.hash,
        )
        dao.replaceDwijPlaylist(
            playlist = playlist,
            entries = trackIds.mapIndexed { position, trackId ->
                LocalPlaylistEntryEntity(
                    playlistId = playlistId,
                    position = position,
                    localTrackId = trackMap[trackId]?.instanceId,
                    rawReference = trackMap[trackId]?.absolutePath
                        ?: trackMap[trackId]?.contentUri,
                )
            },
        )
        Log.d(TAG, "[saveDwijPlaylist] Сохранён '${playlist.name}', tracks=${trackIds.size}")
        DataResult.Success(playlist)
    } catch (error: Exception) {
        Log.e(TAG, "[saveDwijPlaylist] Не удалось сохранить плейлист", error)
        DataResult.Failure(DataError.Storage(error))
    }

    /** Возвращает миниатюру из LRU/диска либо один раз извлекает её из локального файла. */
    fun cover(track: LocalTrackEntity): Flow<Bitmap> = flow {
        val key = coverKey(track)
        coverMemoryCache.get(key)?.let { cached ->
            emit(cached)
            return@flow
        }

        val lock = coverLocks.getOrPut(key, ::Mutex)
        val bitmap = lock.withLock {
            coverMemoryCache.get(key) ?: readDiskCover(key) ?: loadCover(track).also { loaded ->
                coverMemoryCache.put(key, loaded)
                writeDiskCover(key, loaded)
            }
        }
        coverLocks.remove(key, lock)
        emit(bitmap)
    }.flowOn(Dispatchers.IO)

    private fun loadCover(track: LocalTrackEntity): Bitmap {
        val original = track.albumId?.let { albumId ->
            runCatching {
                context.contentResolver.openInputStream(
                    Uri.parse("content://media/external/audio/albumart/$albumId")
                )?.use(BitmapFactory::decodeStream)
            }.getOrNull()
        } ?: embeddedCover(track)
            ?: BitmapFactory.decodeResource(context.resources, R.drawable.logo2)
        return original.downscaled(COVER_MAX_EDGE_PX)
    }

    private fun readDiskCover(key: String): Bitmap? {
        val file = File(coverCacheDirectory, "$key.jpg")
        if (!file.isFile) return null
        return BitmapFactory.decodeFile(file.absolutePath)?.also { bitmap ->
            file.setLastModified(System.currentTimeMillis())
            coverMemoryCache.put(key, bitmap)
        } ?: run {
            file.delete()
            null
        }
    }

    private suspend fun writeDiskCover(key: String, bitmap: Bitmap) {
        val target = File(coverCacheDirectory, "$key.jpg")
        if (target.isFile) return
        val temporary = File.createTempFile("local-cover-", ".tmp", coverCacheDirectory)
        try {
            FileOutputStream(temporary).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, COVER_JPEG_QUALITY, output)
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            if (coverWrites.incrementAndGet() % CACHE_LIMIT_CHECK_INTERVAL == 0) {
                cacheManager.ensureWithinLimit()
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun coverKey(track: LocalTrackEntity): String {
        val raw = "${track.instanceId}|${track.dateModifiedSeconds}|${track.sizeBytes ?: -1L}"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun Bitmap.downscaled(maxEdge: Int): Bitmap {
        val largestEdge = maxOf(width, height)
        if (largestEdge <= maxEdge || largestEdge <= 0) return this
        val scale = maxEdge.toFloat() / largestEdge
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        ).also { scaled ->
            if (scaled !== this) recycle()
        }
    }

    private fun embeddedCover(track: LocalTrackEntity): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(track.contentUri))
            retriever.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } catch (error: Exception) {
            Log.d(TAG, "[embeddedCover] Встроенная обложка недоступна: ${track.instanceId}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun String.normalizedPath(): String = replace('\\', '/').trim().lowercase()

    companion object {
        private const val TAG = "LocalMusicRepository"
        private const val LOCAL_COVER_CACHE_DIR = "local_cover_cache"
        private const val COVER_MEMORY_CACHE_BYTES = 16 * 1024 * 1024
        private const val COVER_MAX_EDGE_PX = 400
        private const val COVER_JPEG_QUALITY = 88
        private const val CACHE_LIMIT_CHECK_INTERVAL = 16

        fun requiredAudioPermission(): String = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT <= 28) {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
        } else {
            arrayOf(requiredAudioPermission())
        }

        fun tracklist(id: String, name: String): LocalTracklist = LocalTracklist(id, name)
    }
}
