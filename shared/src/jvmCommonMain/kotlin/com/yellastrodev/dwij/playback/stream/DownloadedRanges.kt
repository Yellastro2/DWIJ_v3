package com.yellastrodev.dwij.playback.stream

import java.util.TreeMap

/** Объединённые полуоткрытые диапазоны [start, end). Вызывается под lock сессии. */
internal class DownloadedRanges {
    private val ranges = TreeMap<Long, Long>()

    fun add(start: Long, end: Long) {
        require(start >= 0 && end > start)
        var first = start
        var last = end
        ranges.floorEntry(first)?.takeIf { it.value >= first }?.let {
            first = it.key
            last = maxOf(last, it.value)
            ranges.remove(it.key)
        }
        var next = ranges.ceilingEntry(first)
        while (next != null && next.key <= last) {
            last = maxOf(last, next.value)
            ranges.remove(next.key)
            next = ranges.ceilingEntry(first)
        }
        ranges[first] = last
    }

    fun available(position: Long): Long =
        ranges.floorEntry(position)?.value?.minus(position)?.coerceAtLeast(0) ?: 0

    fun nextMissing(position: Long): Long = position + available(position)

    fun missingLength(position: Long, maximum: Long): Long =
        minOf(maximum, ranges.ceilingKey(position)?.minus(position) ?: maximum)
}
