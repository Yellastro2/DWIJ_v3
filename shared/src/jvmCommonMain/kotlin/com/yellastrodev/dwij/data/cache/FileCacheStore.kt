package com.yellastrodev.dwij.data.cache

import com.yellastrodev.dwij.CacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

class FileCacheStore(
    private val directory: File,
    private val cacheManager: CacheManager,
) {

    init {
        if (!directory.exists()) {
            directory.mkdirs()
        }

        cacheManager.registerDir(directory)
    }

    fun read(key: String): ByteArray? {
        val file = fileFor(key)
        if (!file.isFile) return null

        return runCatching {
            file.readBytes().also {
                file.setLastModified(System.currentTimeMillis())
            }
        }.getOrNull()
    }

    suspend fun write(
        key: String,
        bytes: ByteArray,
    ) {
        withContext(Dispatchers.IO) {
            val target = fileFor(key)

            val temporary = File.createTempFile(
                "cache-",
                ".tmp",
                directory,
            )

            try {
                temporary.writeBytes(bytes)

                if (!temporary.renameTo(target)) {
                    temporary.copyTo(
                        target = target,
                        overwrite = true,
                    )
                }

                cacheManager.ensureWithinLimit()
            } finally {
                temporary.delete()
            }
        }
    }

    suspend fun remove(key: String): Boolean {
        return withContext(Dispatchers.IO) {
            val file = fileFor(key)

            !file.exists() || file.delete()
        }
    }

    fun contains(key: String): Boolean {
        return fileFor(key).isFile
    }

    private fun fileFor(key: String): File {
        return File(
            directory,
            "${key.sha256()}.cache",
        )
    }

    private fun String.sha256(): String {
        return MessageDigest
            .getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                "%02x".format(byte)
            }
    }
}