package com.yellastrodev.dwij

import com.yellastrodev.yandexmusiclib.YamLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class CacheManager(
    private val trackDir: File,
    private val coverDir: File,
    private val maxCacheSizeBytes: () -> Long,
    private val logger: YamLogger
) {

    private val mutex = Mutex()

    private val cacheDirs: MutableList<File> = mutableListOf(
        trackDir,
        coverDir
    ).onEach { if (!it.exists()) it.mkdirs() }


    /** Подсчёт общего размера всех файлов во всех кэш‑директориях в байтах*/
    fun getTotalSize(): Long {
        return cacheDirs.flatMap { it.listFiles()?.toList() ?: emptyList() }
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    fun registerDir(dir: File) {
        if (!cacheDirs.contains(dir)) {
            if (!dir.exists()) dir.mkdirs()
            cacheDirs += dir
        }
    }

    suspend fun ensureWithinLimit() = mutex.withLock  {
        val files = cacheDirs.flatMap { it.listFiles()?.toList() ?: emptyList() }
            .filter { it.isFile }
        var totalSize = files.sumOf { it.length() }
        val limit = maxCacheSizeBytes()
        logger.debug("CacheManager", "Кэш ${totalSize / 1024 / 1024}MB из ${limit / 1024 / 1024}MB")

        if (totalSize > limit) {
            logger.debug(
                "CacheManager",
                "Cache ${totalSize / 1024 / 1024}MB > limit ${limit / 1024 / 1024}MB"
            )
            files.sortedBy { it.lastModified() }.forEach { f ->
                if (totalSize <= limit) return
                val size = f.length()
                if (f.delete()) {
                    totalSize -= size
                    logger.debug(
                        "CacheManager",
                        "Удалён ${f.name} (-${size / 1024}KB), осталось ${totalSize / 1024 / 1024}MB"
                    )
                } else {
                    logger.warning("CacheManager", "Не удалось удалить ${f.name}")
                }
            }
        }
    }
}