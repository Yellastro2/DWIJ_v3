package com.yellastrodev.dwij.desktop

import com.yellastrodev.dwij.storage.LocalKeyValueStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * File-backed реализация shared key-value хранилища.
 *
 * Все изменения одного edit-блока записываются одним сохранением файла.
 */
class DesktopLocalKeyValueStore(
    private val file: File,
) : LocalKeyValueStore {

    private val lock = Any()

    private val properties =
        Properties().apply {
            if (file.isFile) {
                file.inputStream().buffered().use(
                    ::load,
                )
            }
        }

    override fun getString(
        key: String,
    ): String? =
        synchronized(lock) {
            properties.getProperty(key)
        }

    override fun getLong(
        key: String,
    ): Long? =
        getString(key)
            ?.toLongOrNull()

    override fun getBoolean(
        key: String,
    ): Boolean? =
        getString(key)
            ?.let { value ->
                when (
                    value.lowercase()
                ) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
            }

    override fun edit(
        block: LocalKeyValueStore.Editor.() -> Unit,
    ) {
        synchronized(lock) {
            EditorImpl()
                .apply(block)

            saveLocked()
        }
    }

    private fun saveLocked() {
        file.parentFile
            ?.mkdirs()

        val temporaryFile =
            File(
                file.parentFile,
                "${file.name}.tmp",
            )

        temporaryFile
            .outputStream()
            .buffered()
            .use { output ->
                properties.store(
                    output,
                    "DWIJ desktop settings",
                )
            }

        runCatching {
            Files.move(
                temporaryFile.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.recoverCatching {
            Files.move(
                temporaryFile.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrThrow()
    }

    private inner class EditorImpl :
        LocalKeyValueStore.Editor {

        override fun putString(
            key: String,
            value: String,
        ) {
            properties.setProperty(
                key,
                value,
            )
        }

        override fun putLong(
            key: String,
            value: Long,
        ) {
            putString(
                key,
                value.toString(),
            )
        }

        override fun putBoolean(
            key: String,
            value: Boolean,
        ) {
            putString(
                key,
                value.toString(),
            )
        }

        override fun remove(
            key: String,
        ) {
            properties.remove(key)
        }
    }
}
