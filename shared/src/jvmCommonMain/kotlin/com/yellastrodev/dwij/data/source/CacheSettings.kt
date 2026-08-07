package com.yellastrodev.dwij.storage

import com.yellastrodev.dwij.CACHE_SIZE
import com.yellastrodev.dwij.DEFAULT_CACHE_SIZE

/**
 * Настройки общего файлового кэша приложения.
 */
interface CacheSettings {

    var maxSizeBytes: Long
}

/**
 * Постоянная реализация [CacheSettings] поверх общего key-value storage.
 */
class StoredCacheSettings(
    private val storage: LocalKeyValueStore,
) : CacheSettings {

    override var maxSizeBytes: Long
        get() =
            storage.getLong(CACHE_SIZE)
                ?: DEFAULT_CACHE_SIZE

        set(value) {
            storage.edit {
                putLong(
                    CACHE_SIZE,
                    value,
                )
            }
        }
}