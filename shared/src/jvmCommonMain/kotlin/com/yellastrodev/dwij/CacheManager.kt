package com.yellastrodev.dwij

import com.yellastrodev.yamusicsdk.YamLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Общий LRU-лимит: обычный файл вытесняется отдельно, каталог VK-трека — целиком со всеми версиями. */
class CacheManager(
    private val trackDir: File,
    private val coverDir: File,
    private val maxCacheSizeBytes: () -> Long,
    private val logger: YamLogger
) {

    private val mutex = Mutex()
    private val groupedDirs = mutableSetOf<File>()
    private val groupLocks = ConcurrentHashMap<String, Any>()

    private val cacheDirs: MutableList<File> = mutableListOf(
        trackDir,
        coverDir
    ).map { it.absoluteFile }.distinct().toMutableList().onEach { if (!it.exists()) it.mkdirs() }


    /** Считает файлы и содержимое групп ровно один раз, включая частично загруженные VK-треки. */
    fun getTotalSize(): Long = cacheEntries().sumOf(::entrySize)

    /** Регистрирует плоский каталог обычных cache-файлов. */
    @Synchronized
    fun registerDir(dir: File) {
        val absolute = dir.absoluteFile
        if (!cacheDirs.contains(absolute)) {
            if (!dir.exists()) dir.mkdirs()
            cacheDirs += absolute
        }
    }

    /** Непосредственные подкаталоги считаются неделимыми группами; старые плоские файлы остаются совместимыми. */
    @Synchronized
    fun registerGroupedDir(dir: File) {
        registerDir(dir)
        groupedDirs += dir.absoluteFile
    }

    /** Не позволяет LRU удалить каталог посреди чтения, публикации файла либо переноса старого ресурса. */
    fun <T> withCacheGroup(directory: File, block: () -> T): T =
        synchronized(groupLocks.getOrPut(directory.absolutePath) { Any() }) { block() }

    /** Возвращает файлы и каталоги-группы; вложенная регистрация не дублирует уже учитываемый трек. */
    private fun cacheEntries(): List<File> {
        val (directories, grouped) = synchronized(this) { cacheDirs.toList() to groupedDirs.toSet() }
        return directories.filterNot { it.parentFile in grouped }.flatMap { directory ->
            directory.listFiles().orEmpty().filter { it.isFile || (directory in grouped && it.isDirectory) }
        }.distinctBy { it.absolutePath }
    }

    /** Полный размер неделимой записи; для группы включает описание и все её ресурсы. */
    private fun entrySize(entry: File): Long = if (entry.isDirectory)
        entry.walkTopDown().filter(File::isFile).sumOf(File::length) else if (entry.isFile) entry.length() else 0L

    /** Удаляет самые давно использованные записи; частичное удаление каталога учитывается по фактически освобождённым байтам. */
    suspend fun ensureWithinLimit() = mutex.withLock  {
        val entries = cacheEntries()
        var totalSize = entries.sumOf(::entrySize)
        val limit = maxCacheSizeBytes()
        logger.debug("CacheManager", "[ensureWithinLimit] Кеш ${totalSize / 1024 / 1024} МБ из ${limit / 1024 / 1024} МБ")

        if (totalSize > limit) {
            entries.sortedBy { it.lastModified() }.forEach { entry ->
                if (totalSize <= limit) return
                val group = entry.isDirectory
                withCacheGroup(if (group) entry else entry.parentFile) {
                    val before = entrySize(entry)
                    val deleted = !entry.exists() || if (group) entry.deleteRecursively() else entry.delete()
                    val removed = (before - entrySize(entry)).coerceAtLeast(0L)
                    totalSize -= removed
                    if (deleted) {
                        logger.debug("CacheManager", "[ensureWithinLimit] Удалена ${if (group) "группа VK-трека" else "запись"} ${entry.name}: ${removed / 1024} КиБ")
                    } else {
                        logger.warning("CacheManager", "[ensureWithinLimit] Не удалось полностью удалить ${entry.name}")
                    }
                }
            }
        }
    }
}
