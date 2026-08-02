package com.yellastrodev.dwij.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.yellastrodev.dwij.CacheManager
import com.yellastrodev.dwij.DIR_COVER_CACHE
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.entities.iPlaylist
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.entities.CoverSize
import com.yellastrodev.yandexmusiclib.network.YamResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CoverRepository(
    private val context: Context,
    private val fClient: YamApiClient,
    private val cacheManager: CacheManager,
    private val scope: CoroutineScope

) {

    private val cacheDir = File(context.cacheDir, DIR_COVER_CACHE).apply {
        if (!exists()) mkdirs()
    }

    // in-memory cache
    private val memoryCache = mutableMapOf<String, Bitmap>()

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
                    R.drawable.logo2
                )
            }
            else -> {
                return BitmapFactory.decodeResource(context.resources, R.drawable.logo2)
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
        }?: return BitmapFactory.decodeResource(context.resources, R.drawable.logo2)

    }

    suspend fun getCover(playlist: iPlaylist, size: CoverSize = CoverSize.`200x200`): Bitmap {
        if (playlist is dYaPlaylist) {
            val key = "playlist_" + keyForSize(playlist.playlistUuid,size)
            return getCover(key, playlist.ogImageUri!!, size)
        }
        return BitmapFactory.decodeResource(context.resources, R.drawable.logo2)
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
                memoryCache[exactKey] = it
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
            memoryCache[exactKey] = bitmap
            withContext(Dispatchers.IO) {
                exactFile.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                }
            }
        } ?: emit(BitmapFactory.decodeResource(context.resources, R.drawable.logo2))
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
                    memoryCache[altKey] = it
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
                memoryCache[key] = bitmap
                logCoverResult(key, "диск", startedNanos)
                return bitmap
            }
        }

        // 3. Если нет, загружаем с сети
        val bitmap = downloadCover(url, size)

        // сохраняем в память
        memoryCache[key] = bitmap

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
    }
}
