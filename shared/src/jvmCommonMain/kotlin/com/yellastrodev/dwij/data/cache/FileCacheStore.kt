package com.yellastrodev.dwij.data.cache

import com.yellastrodev.dwij.CacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Атомарное LRU-хранилище; group объединяет ресурсы трека в каталог с общей давностью использования. */
class FileCacheStore private constructor(
    private val directory: File,
    private val cacheManager: CacheManager,
    private val groupDirectory: File?,
) {
    /** Создаёт прежнее плоское хранилище обложек или ресурсов; формат имён файлов сохраняется. */
    constructor(directory: File, cacheManager: CacheManager) : this(directory.absoluteFile, cacheManager, null)

    /*
     * Запись cache-файлов должна быть сериализована.
     *
     * В частности, один и тот же cover может одновременно запросить:
     * - UI;
     * - Windows SMTC artwork;
     * - другие consumers.
     *
     * Без mutex два writer'а могут одновременно пройти cache miss
     * и попытаться опубликовать один и тот же target-файл.
     */
    private val writeMutex =
        Mutex()

    init {
        if (groupDirectory == null && !directory.exists()) {
            directory.mkdirs()
        }

        if (groupDirectory == null) cacheManager.registerDir(directory)
    }

    /** Возвращает ленивое хранилище одного трека; чтение отсутствующей группы не создаёт пустой каталог. */
    fun group(id: String): FileCacheStore {
        enableGrouping()
        val grouped = File(directory, "track-${id.sha256()}")
        return FileCacheStore(grouped, cacheManager, grouped)
    }

    /** Включает подсчёт/вытеснение каталогов сразу при старте, до первого обращения к треку. */
    fun enableGrouping() { cacheManager.registerGroupedDir(directory) }

    /** Читает целый ресурс под блокировкой группы и обновляет давность использования всего трека. */
    fun read(
        key: String,
    ): ByteArray? = cacheManager.withCacheGroup(directory) {
        val file =
            fileFor(
                key,
            )

        if (!file.isFile) {
            return@withCacheGroup null
        }

        runCatching {
            file
                .readBytes()
                .also {
                    file.setLastModified(
                        System.currentTimeMillis(),
                    )
                    groupDirectory?.setLastModified(System.currentTimeMillis())
                }
        }.getOrNull()
    }

    /** Публикует ресурс; replaceExisting атомарно заменяет изменяемый индекс, сохраняя общий лимит кеша. */
    suspend fun write(
        key: String,
        bytes: ByteArray,
        replaceExisting: Boolean = false,
    ) {
        withContext(
            Dispatchers.IO,
        ) {
            var createdNewFile =
                false

            writeMutex.withLock {
                cacheManager.withCacheGroup(directory) {
                    directory.mkdirs()
                    val target =
                        fileFor(
                            key,
                        )

                    /*
                     * Это cache, поэтому существующий опубликованный файл
                     * уже считается успешным результатом.
                     *
                     * Особенно важно для гонки:
                     *
                     * request A: cache miss -> download
                     * request B: cache miss -> download
                     * request A: write
                     * request B: write
                     *
                     * B после ожидания mutex просто увидит готовый target
                     * и не станет пытаться перезаписывать его.
                     */
                    if (target.isFile && !replaceExisting) {
                        target.setLastModified(
                            System.currentTimeMillis(),
                        )
                        groupDirectory?.setLastModified(System.currentTimeMillis())

                        return@withCacheGroup
                    }

                    val temporary =
                        File.createTempFile(
                            "cache-",
                            ".tmp",
                            directory,
                        )

                    try {
                        temporary.writeBytes(
                            bytes,
                        )

                        /*
                         * temporary находится в той же директории,
                         * поэтому renameTo обычно является самым дешёвым
                         * способом атомарно опубликовать готовый cache-файл.
                         */
                        if (replaceExisting) {
                            try {
                                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                            } catch (_: AtomicMoveNotSupportedException) {
                                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            }
                        } else if (
                            !temporary.renameTo(
                                target,
                            )
                        ) {
                            /*
                             * На случай если target каким-либо образом
                             * появился извне между проверкой и rename.
                             */
                            if (!target.isFile) {
                                temporary.copyTo(
                                    target =
                                        target,
                                    overwrite =
                                        false,
                                )
                            }
                        }

                        if (!target.isFile) {
                            error(
                                "Не удалось сохранить cache-файл: " +
                                        target.absolutePath,
                            )
                        }

                        target.setLastModified(
                            System.currentTimeMillis(),
                        )
                        groupDirectory?.setLastModified(System.currentTimeMillis())

                        createdNewFile =
                            true
                    } finally {
                        temporary.delete()
                    }
                }
            }

            /*
             * Не держим writeMutex во время suspend-вызова CacheManager.
             *
             * CacheManager имеет собственный Mutex.
             */
            if (createdNewFile) {
                cacheManager
                    .ensureWithinLimit()
            }
        }
    }

    /** Удаляет повреждённую запись под той же блокировкой, что публикация и групповое вытеснение. */
    suspend fun remove(
        key: String,
    ): Boolean {
        return withContext(
            Dispatchers.IO,
        ) {
            writeMutex.withLock {
                cacheManager.withCacheGroup(directory) {
                    val file =
                        fileFor(
                            key,
                        )

                    !file.exists() ||
                            file.delete()
                }
            }
        }
    }

    /** Перемещает старую запись в группу без копирования и временного удвоения размера под LRU-лимитом. */
    suspend fun adopt(key: String, legacy: FileCacheStore): Unit = withContext(Dispatchers.IO) {
        if (directory == legacy.directory) return@withContext
        legacy.writeMutex.withLock {
            writeMutex.withLock {
                cacheManager.withCacheGroup(legacy.directory) {
                    cacheManager.withCacheGroup(directory) destination@{
                        val source = legacy.fileFor(key)
                        if (!source.isFile) return@destination
                        directory.mkdirs()
                        val target = fileFor(key)
                        if (target.isFile) {
                            source.delete()
                        } else {
                            try {
                                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                            } catch (_: AtomicMoveNotSupportedException) {
                                Files.move(source.toPath(), target.toPath())
                            }
                        }
                        groupDirectory?.setLastModified(System.currentTimeMillis())
                    }
                }
            }
        }
    }

    fun contains(
        key: String,
    ): Boolean {
        return fileFor(
            key,
        ).isFile
    }

    private fun fileFor(
        key: String,
    ): File {
        return File(
            directory,
            "${key.sha256()}.cache",
        )
    }

    private fun String.sha256():
            String {

        return MessageDigest
            .getInstance(
                "SHA-256",
            )
            .digest(
                toByteArray(
                    Charsets.UTF_8,
                ),
            )
            .joinToString(
                separator = "",
            ) { byte ->
                "%02x".format(
                    byte,
                )
            }
    }
}
