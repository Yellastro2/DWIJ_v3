package com.yellastrodev.dwij.data.repo

import android.content.Context
import android.net.Uri
import android.preference.PreferenceManager
import android.util.Log
import com.yellastrodev.dwij.CACHE_SIZE
import com.yellastrodev.dwij.CacheManager
import com.yellastrodev.dwij.DEFAULT_CACHE_SIZE
import com.yellastrodev.dwij.DIR_TRACK_CACHE
import com.yellastrodev.yandexmusiclib.kot_utils.yTrack.Companion.Mp3LinkResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

@Suppress("DEPRECATION")
class TrackCacheRepository(
    val context: Context,
    val trackRepo: TrackRepository,
    private val cacheManager: CacheManager
) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

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
                val result = trackRepo.getTrackUrl(trackId)
                when (result) {
                    is Mp3LinkResult.Success -> {
                        val bytes = URL(result.url).readBytes()
                        file.writeBytes(bytes)
                    }
                    is Mp3LinkResult.Error -> {
                        Log.e("TrackCacheRepository", "Ошибка при скачивании трека $trackId: ${result.cause}")
                        throw Exception(result.cause.toString())
                    }
                }
                GlobalScope.launch(Dispatchers.IO) {
                    cacheManager.ensureWithinLimit()
                }
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