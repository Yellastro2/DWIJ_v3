package com.yellastrodev.dwij.data.repo

import android.content.Context
import android.net.Uri
import android.util.Log
import com.yellastrodev.dwij.CacheManager
import com.yellastrodev.dwij.DIR_TRACK_CACHE
import com.yellastrodev.dwij.data.DataResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Suppress("DEPRECATION")
class TrackCacheRepository(
    val context: Context,
    val trackRepo: TrackRepository,
    private val cacheManager: CacheManager
) {

    private val cacheDir = File(context.cacheDir, DIR_TRACK_CACHE).apply {
        if (!exists()) mkdirs()
    }

    fun getLocalFile(trackId: String): File = File(cacheDir, "$trackId.mp3")

    fun isCached(trackId: String): Boolean = getLocalFile(trackId).exists()

    /**
     * Возвращает Uri: если трек закеширован → локальный файл,
     * иначе качает с сервера и кладёт в кэш.
     */
    suspend fun getOrDownload(trackId: String): Uri =
        withContext(Dispatchers.IO) {
            val file = getLocalFile(trackId)
            if (!file.exists()) {
                Log.d("TrackCacheRepository", "Трека $trackId нет в кэше, скачиваем")
                val result = trackRepo.getTrackBytes(trackId)
                when (result) {
                    is DataResult.Success -> {
                        file.writeBytes(result.value)
                        Log.d(
                            "TrackCacheRepository",
                            "Трек $trackId загружен: ${result.value.size} байт"
                        )
                    }
                    is DataResult.Failure -> {
                        Log.e("TrackCacheRepository", "Ошибка при скачивании трека $trackId: ${result.error}")
                        throw Exception(result.error.toString())
                    }
                }
                cacheManager.ensureWithinLimit()
            }else
                Log.d("TrackCacheRepository", "Трек $trackId есть в кэше")
            Uri.fromFile(file)
        }

    /** Очистка по одному треку */
    fun remove(trackId: String) {
        getLocalFile(trackId).delete()
    }

    /** Очистка всего кэша */
    fun clear() {
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
    }

}
