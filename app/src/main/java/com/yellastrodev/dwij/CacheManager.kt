package com.yellastrodev.dwij

import android.content.Context
import android.preference.PreferenceManager
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class CacheManager(
    private val context: Context
) {

    private val mutex = Mutex()

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    private val maxCacheSizeBytes: Long
        get() = prefs.getLong(CACHE_SIZE, DEFAULT_CACHE_SIZE)

    private val cacheDirs: List<File> = listOf(
        File(context.cacheDir, DIR_TRACK_CACHE),
        File(context.cacheDir, DIR_COVER_CACHE)
    ).onEach { if (!it.exists()) it.mkdirs() }


    /** Подсчёт общего размера всех файлов во всех кэш‑директориях в байтах*/
    fun getTotalSize(): Long {
        return cacheDirs.flatMap { it.listFiles()?.toList() ?: emptyList() }
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    fun registerDir(dir: File) {
        if (!cacheDirs.contains(dir)) {
            cacheDirs + dir
        }
    }

    suspend fun ensureWithinLimit() = mutex.withLock  {
        val files = cacheDirs.flatMap { it.listFiles()?.toList() ?: emptyList() }
            .filter { it.isFile }
        var totalSize = files.sumOf { it.length() }
        Log.d("CacheManager", "Кэш ${totalSize / 1024 / 1024}MB из ${maxCacheSizeBytes / 1024 / 1024}MB")

        if (totalSize > maxCacheSizeBytes) {
            Log.d(
                "CacheManager",
                "Cache ${totalSize / 1024 / 1024}MB > limit ${maxCacheSizeBytes / 1024 / 1024}MB"
            )
            files.sortedBy { it.lastModified() }.forEach { f ->
                if (totalSize <= maxCacheSizeBytes) return
                val size = f.length()
                if (f.delete()) {
                    totalSize -= size
                    Log.d(
                        "CacheManager",
                        "Удалён ${f.name} (-${size / 1024}KB), осталось ${totalSize / 1024 / 1024}MB"
                    )
                } else {
                    Log.w("CacheManager", "Не удалось удалить ${f.name}")
                }
            }
        }
    }
}
