package com.yellastrodev.dwij.data.cache

import com.yellastrodev.dwij.CacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class FileCacheStore(
    private val directory: File,
    private val cacheManager: CacheManager,
) {

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
        if (!directory.exists()) {
            directory.mkdirs()
        }

        cacheManager.registerDir(
            directory,
        )
    }

    fun read(
        key: String,
    ): ByteArray? {
        val file =
            fileFor(
                key,
            )

        if (!file.isFile) {
            return null
        }

        return runCatching {
            file
                .readBytes()
                .also {
                    file.setLastModified(
                        System.currentTimeMillis(),
                    )
                }
        }.getOrNull()
    }

    suspend fun write(
        key: String,
        bytes: ByteArray,
    ) {
        withContext(
            Dispatchers.IO,
        ) {
            var createdNewFile =
                false

            writeMutex.withLock {
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
                if (target.isFile) {
                    target.setLastModified(
                        System.currentTimeMillis(),
                    )

                    return@withLock
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
                    if (
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

                    createdNewFile =
                        true
                } finally {
                    temporary.delete()
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

    suspend fun remove(
        key: String,
    ): Boolean {
        return withContext(
            Dispatchers.IO,
        ) {
            writeMutex.withLock {
                val file =
                    fileFor(
                        key,
                    )

                !file.exists() ||
                        file.delete()
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