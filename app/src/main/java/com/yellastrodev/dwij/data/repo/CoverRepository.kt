package com.yellastrodev.dwij.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.yellastrodev.dwij.CacheManager
import com.yellastrodev.dwij.DIR_COVER_CACHE
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.data.entities.LocalTrackEntity
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.entities.iPlaylist
import com.yellastrodev.dwij.utils.DwLruCache
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.entities.CoverSize
import com.yellastrodev.yandexmusiclib.network.YamResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class CoverRepository(
    private val context: Context,
    private val fClient: YamApiClient,
    private val cacheManager: CacheManager,
    private val scope: CoroutineScope

) {

    private val cacheDir = File(context.cacheDir, DIR_COVER_CACHE).apply {
        if (!exists()) mkdirs()
    }
    private val localCacheDir = File(context.cacheDir, LOCAL_COVER_CACHE_DIR).apply {
        if (!exists()) mkdirs()
    }

    private val memoryCache = object : DwLruCache<String, Bitmap>(COVER_MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val localCoverLocks = ConcurrentHashMap<String, Mutex>()
    private val localCoverWrites = AtomicInteger()

    init {
        cacheManager.registerDir(cacheDir)
        cacheManager.registerDir(localCacheDir)
    }

    private suspend fun downloadCover(url: String, size: CoverSize): Bitmap{
        val result = fClient.coverBytes(url, size)
        when(result){
            is YamResult.Success -> {
                return BitmapFactory.decodeByteArray(
                    result.value,
                    0,
                    result.value.size
                ) ?: BitmapFactory.decodeResource(
                    context.resources,
                    R.drawable.ic_player_play_v2
                )
            }
            else -> {
                return BitmapFactory.decodeResource(context.resources, R.drawable.ic_player_play_v2)
            }
        }
//        return stream.use {
//            BitmapFactory.decodeStream(it)
//        }
    }

    private fun keyForSize(baseKey: String, size: CoverSize) =
        "$baseKey-${size.name}"

    suspend fun getCover(track: dYaTrack, size: CoverSize = CoverSize.`200x200`): Bitmap {

        track.getCoverUriAny()?. let {
            return getCover(keyForSize(track.id, size), it, size)
        }?: return BitmapFactory.decodeResource(context.resources, R.drawable.ic_player_play_v2)

    }

    suspend fun getCover(playlist: iPlaylist, size: CoverSize = CoverSize.`200x200`): Bitmap {
        if (playlist is dYaPlaylist) {
            val key = "playlist_" + keyForSize(playlist.playlistUuid,size)
            return getCover(key, playlist.ogImageUri!!, size)
        }
        return BitmapFactory.decodeResource(context.resources, R.drawable.ic_player_play_v2)
    }

    suspend fun getCover(playlist: dYaPlaylist, size: CoverSize = CoverSize.`200x200`): Bitmap {

        val key = "playlist_" + keyForSize(playlist.playlistUuid,size)
        return getCover(key, playlist.ogImageUri!!, size)
    }

    fun getCoverFlow(
        track: dYaTrack,
        size: CoverSize = CoverSize.`200x200`
    ): Flow<Bitmap> = flow {
        Log.d("CoverRepository", "getCoverFlow called, трек ${track.id}, размер $size")
        val exactKey = keyForSize(track.id, size)

        // 1. Память
        memoryCache[exactKey]?.let {

            Log.d("CoverRepository", "getCoverFlow найден кешированый файл")
            emit(it)
            return@flow
        }

        // 2. Диск
        val exactFile = File(cacheDir, "$exactKey.JPEG")
        if (exactFile.exists()) {
            Log.d("CoverRepository", "getCoverFlow найден файл на диске")
            BitmapFactory.decodeFile(exactFile.absolutePath)?.let {
                memoryCache.put(exactKey, it)
                emit(it)
                return@flow
            }
        }

        // 3. Fallback — меньший размер
        val smaller = findSmallerCachedVersion(track.id, size)
        if (smaller != null) emit(smaller)

        // 4. Загрузка нужного размера
        track.ogImageUri?.let { url ->
            Log.d("CoverRepository", "getCoverFlow загружаем обложку")
            val bitmap = downloadCover(url, size)
            Log.d("CoverRepository", "getCoverFlow обложка загружена онлайн")
            emit(bitmap)
            memoryCache.put(exactKey, bitmap)
            withContext(Dispatchers.IO) {
                exactFile.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                }
            }
        } ?: emit(BitmapFactory.decodeResource(context.resources, R.drawable.ic_player_play_v2))
    }

    private fun findSmallerCachedVersion(trackId: String, size: CoverSize): Bitmap? {
        Log.d("CoverRepository", "findSmallerCachedVersion called")
        val sizesDescending = CoverSize.values()
            .sortedByDescending { it.name.removePrefix("`").substringBefore("x").toInt() }
        val targetIndex = sizesDescending.indexOf(size)
        val smallerSizes = sizesDescending.drop(targetIndex + 1)

        for (s in smallerSizes) {
            val altKey = keyForSize(trackId, s)
            memoryCache[altKey]?.let { return it }
            val file = File(cacheDir, "$altKey.JPEG")
            if (file.exists()) {
                Log.d("CoverRepository", "findSmallerCachedVersion найден малый файл на диске: $altKey")
                BitmapFactory.decodeFile(file.absolutePath)?.let {
                    memoryCache.put(altKey, it)
                    return it
                }
            }
        }
        return null
    }



    /** Возвращает обложку и в debug-логах отмечает фактический слой кеша и его задержку. */
    suspend fun getCover(key: String, url: String, size: CoverSize = CoverSize.`200x200`): Bitmap {
        val startedNanos = SystemClock.elapsedRealtimeNanos()

        // 1. Сначала память
        memoryCache[key]?.let { bitmap ->
            logCoverResult(key, "память", startedNanos)
            return bitmap
        }

        // 2. Потом диск
        val file = File(cacheDir, "$key.JPEG")
        if (file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath)?.let { bitmap ->
                memoryCache.put(key, bitmap)
                logCoverResult(key, "диск", startedNanos)
                return bitmap
            }
        }

        // 3. Если нет, загружаем с сети
        val bitmap = downloadCover(url, size)

        // сохраняем в память
        memoryCache.put(key, bitmap)

        // сохраняем на диск асинхронно
        scope.launch(Dispatchers.IO) {
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
            cacheManager.ensureWithinLimit()
        }
        logCoverResult(key, "сеть", startedNanos)
        return bitmap
    }

    /** Возвращает локальную обложку из памяти/диска либо извлекает её из медиафайла. */
    fun getCoverFlow(track: LocalTrackEntity): Flow<Bitmap> = flow {
        val key = localCoverKey(track)
        memoryCache[key]?.let { cached ->
            emit(cached)
            return@flow
        }

        val lock = localCoverLocks.computeIfAbsent(key) { Mutex() }
        val bitmap = try {
            lock.withLock {
                memoryCache[key]
                    ?: readLocalDiskCover(key)
                    ?: loadLocalCover(track).also { loaded ->
                        memoryCache.put(key, loaded)
                        writeLocalDiskCover(key, loaded)
                    }
            }
        } finally {
            localCoverLocks.remove(key, lock)
        }
        emit(bitmap)
    }.flowOn(Dispatchers.IO)

    private fun loadLocalCover(track: LocalTrackEntity): Bitmap {
        val original = track.albumId?.let { albumId ->
            runCatching {
                context.contentResolver.openInputStream(
                    Uri.parse("content://media/external/audio/albumart/$albumId")
                )?.use(BitmapFactory::decodeStream)
            }.getOrNull()
        } ?: embeddedLocalCover(track)
            ?: BitmapFactory.decodeResource(context.resources, R.drawable.ic_player_play_v2)
        return original.downscaled(COVER_MAX_EDGE_PX)
    }

    private fun readLocalDiskCover(key: String): Bitmap? {
        val file = File(localCacheDir, "$key.jpg")
        if (!file.isFile) return null
        return BitmapFactory.decodeFile(file.absolutePath)?.also { bitmap ->
            file.setLastModified(System.currentTimeMillis())
            memoryCache.put(key, bitmap)
        } ?: run {
            file.delete()
            null
        }
    }

    private suspend fun writeLocalDiskCover(key: String, bitmap: Bitmap) {
        val target = File(localCacheDir, "$key.jpg")
        if (target.isFile) return
        val temporary = File.createTempFile("local-cover-", ".tmp", localCacheDir)
        try {
            FileOutputStream(temporary).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, COVER_JPEG_QUALITY, output)
            }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            if (localCoverWrites.incrementAndGet() % CACHE_LIMIT_CHECK_INTERVAL == 0) {
                cacheManager.ensureWithinLimit()
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun localCoverKey(track: LocalTrackEntity): String {
        val raw = "${track.instanceId}|${track.dateModifiedSeconds}|${track.sizeBytes ?: -1L}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "local-$digest"
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

    private fun embeddedLocalCover(track: LocalTrackEntity): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(track.contentUri))
            retriever.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } catch (error: Exception) {
            Log.d(TAG, "[embeddedLocalCover] Встроенная обложка недоступна: ${track.instanceId}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Пишет только действительно медленный результат, не засоряя logd кеш-попаданиями. */
    private fun logCoverResult(key: String, source: String, startedNanos: Long) {
        val durationMillis =
            (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000L
        if (durationMillis < SLOW_COVER_LOG_MILLIS) return
        Log.d(
            TAG,
            "[getCover] key=$key, источник=$source, время=$durationMillis мс",
        )
    }

    companion object {
        private const val TAG = "CoverRepository"
        private const val SLOW_COVER_LOG_MILLIS = 20L
        private const val LOCAL_COVER_CACHE_DIR = "local_cover_cache"
        private const val COVER_MEMORY_CACHE_BYTES = 16 * 1024 * 1024
        private const val COVER_MAX_EDGE_PX = 400
        private const val COVER_JPEG_QUALITY = 88
        private const val CACHE_LIMIT_CHECK_INTERVAL = 16
    }
}
