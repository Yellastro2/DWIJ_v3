package com.yellastrodev.dwij.storage

/**
 * Низкоуровневое постоянное key-value хранилище.
 *
 * Shared-код знает только этот контракт и не зависит от конкретного
 * механизма хранения платформы.
 */
interface LocalKeyValueStore {

    fun getString(key: String): String?

    fun getLong(key: String): Long?

    fun getBoolean(key: String): Boolean?

    /**
     * Выполняет группу изменений одной операцией платформенного хранилища.
     */
    fun edit(
        block: Editor.() -> Unit,
    )

    interface Editor {

        fun putString(
            key: String,
            value: String,
        )

        fun putLong(
            key: String,
            value: Long,
        )

        fun putBoolean(
            key: String,
            value: Boolean,
        )

        fun remove(key: String)
    }
}