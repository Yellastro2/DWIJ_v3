package com.yellastrodev.dwij.utils

/**
 * Небольшой потокобезопасный LRU-кэш без зависимостей от Android.
 *
 * [maxSize] задаёт максимальный суммарный размер элементов. По умолчанию каждый
 * элемент занимает одну единицу; для другого способа подсчёта можно переопределить [sizeOf].
 */
open class DwLruCache<K : Any, V : Any>(
    val maxSize: Int,
) {
    private val entries = LinkedHashMap<K, V>(0, LOAD_FACTOR, true)
    private var currentSize = 0

    init {
        require(maxSize > 0) { "maxSize должен быть больше нуля" }
    }

    @Synchronized
    operator fun get(key: K): V? = entries[key]

    /** Добавляет значение, обновляет его при совпадении ключа и возвращает предыдущее. */
    @Synchronized
    fun put(key: K, value: V): V? {
        currentSize += checkedSizeOf(key, value)
        val previous = entries.put(key, value)
        if (previous != null) {
            currentSize -= checkedSizeOf(key, previous)
        }
        trimToSize(maxSize)
        return previous
    }

    /** Удаляет значение. Отсутствующий ключ считается ошибкой вызывающего кода. */
    @Synchronized
    fun remove(key: K): V {
        val value = entries.remove(key)
            ?: throw NoSuchElementException("В кэше нет значения для ключа $key")
        currentSize -= checkedSizeOf(key, value)
        return value
    }

    /** Возвращает независимую копию содержимого от старых элементов к недавно использованным. */
    @Synchronized
    fun snapshot(): Map<K, V> = LinkedHashMap(entries)

    @Synchronized
    fun size(): Int = currentSize

    @Synchronized
    fun evictAll() {
        entries.clear()
        currentSize = 0
    }

    protected open fun sizeOf(key: K, value: V): Int = 1

    private fun checkedSizeOf(key: K, value: V): Int =
        sizeOf(key, value).also { itemSize ->
            require(itemSize >= 0) {
                "Отрицательный размер элемента: key=$key, value=$value"
            }
        }

    private fun trimToSize(targetSize: Int) {
        val iterator = entries.entries.iterator()
        while (currentSize > targetSize && iterator.hasNext()) {
            val eldest = iterator.next()
            currentSize -= checkedSizeOf(eldest.key, eldest.value)
            iterator.remove()
        }
    }

    private companion object {
        const val LOAD_FACTOR = 0.75f
    }
}
