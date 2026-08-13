package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.CacheManager
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.yamusicsdk.YamLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Suppress("DEPRECATION")
class TrackCacheRepository(
    private val cacheDir: File,
    val trackRepo: TrackRepository,
    private val cacheManager: CacheManager,
    private val logger: YamLogger,
    private val onAuthorizationRequired: () -> Unit,
) {


    fun getLocalFile(trackId: String): File = File(cacheDir, "$trackId.mp3")

    fun isCached(trackId: String): Boolean = getLocalFile(trackId).exists()

    /**
     * Возвращает Uri: если трек закеширован → локальный файл,
     * иначе качает с сервера и кладёт в кэш.
     */
    suspend fun getOrDownload(trackId: String): String =
        withContext(Dispatchers.IO) {
            val file = getLocalFile(trackId)
            if (!file.exists()) {
                logger.debug("TrackCacheRepository", "Трека $trackId нет в кэше, скачиваем")
                val result = trackRepo.getTrackBytes(trackId)
                when (result) {
                    is DataResult.Success -> {
                        file.writeBytes(result.value)
                        logger.debug(
                            "TrackCacheRepository",
                            "Трек $trackId загружен: ${result.value.size} байт"
                        )
                    }
                    is DataResult.Failure -> {
                        if (result.error == DataError.Unauthorized) {
                            onAuthorizationRequired()
                        }
                        logger.error("TrackCacheRepository", "Ошибка при скачивании трека $trackId: ${result.error}")
                        throw Exception(result.error.toString())
                    }
                }
                cacheManager.ensureWithinLimit()
            }else
                logger.debug("TrackCacheRepository", "Трек $trackId есть в кэше")
            file.toURI().toString()
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
