package com.yellastrodev.dwij.desktop.playback

import kotlin.random.Random

/**
 * Хранит перемешанный порядок индексов и позицию в истории воспроизведения.
 * Уже пройденная часть не перемешивается при выборе трека или расширении очереди.
 */
internal class DesktopShuffleOrder(
    private val random: Random =
        Random.Default,
) {

    private var order =
        emptyList<Int>()

    private var position =
        -1

    private var queueSize =
        0

    fun reset(
        queueSize: Int,
        currentIndex: Int,
    ) {
        if (
            queueSize <= 0 ||
            currentIndex !in 0 until queueSize
        ) {
            clear()
            return
        }

        order =
            listOf(
                currentIndex,
            ) +
                    (0 until queueSize)
                        .filterNot {
                            it == currentIndex
                        }
                        .shuffled(
                            random,
                        )

        position =
            0

        this.queueSize =
            queueSize
    }

    fun clear() {
        order =
            emptyList()

        position =
            -1

        queueSize =
            0
    }

    fun next(
        repeatAll: Boolean,
    ): Int? {
        if (position !in order.indices) {
            return null
        }

        if (position < order.lastIndex) {
            position +=
                1

            return order[position]
        }

        if (!repeatAll) {
            return null
        }

        val lastIndex =
            order[position]

        val nextCycle =
            (0 until queueSize)
                .shuffled(
                    random,
                )
                .avoidFirst(
                    lastIndex,
                )

        order =
            order + nextCycle

        position +=
            1

        return order[position]
    }

    /** Смотрит вперёд по уже определённому shuffle-порядку, не двигая позицию. */
    fun peekNext(
        count: Int,
    ): List<Int> {
        if (count <= 0 || position !in order.indices) {
            return emptyList()
        }

        return order
            .drop(position + 1)
            .take(count)
    }

    fun previous(): Int? {
        if (position !in order.indices) {
            return null
        }

        if (position > 0) {
            position -=
                1
        }

        return order[position]
    }

    fun select(
        index: Int,
    ) {
        if (index !in 0 until queueSize) {
            return
        }

        if (position !in order.indices) {
            reset(
                queueSize =
                    queueSize,
                currentIndex =
                    index,
            )

            return
        }

        if (order[position] == index) {
            return
        }

        val history =
            order.take(
                position + 1,
            )

        val future =
            order.drop(
                position + 1,
            ).filterNot {
                it == index
            }

        order =
            history + index + future

        position =
            history.size
    }

    fun append(
        previousQueueSize: Int,
        newQueueSize: Int,
    ) {
        if (
            newQueueSize <= previousQueueSize
        ) {
            return
        }

        queueSize =
            newQueueSize

        if (position !in order.indices) {
            return
        }

        val added =
            (previousQueueSize until newQueueSize)
                .shuffled(
                    random,
                )

        order =
            order.take(
                position + 1,
            ) +
                    order.drop(
                        position + 1,
                    ) +
                    added

    }

    private fun List<Int>.avoidFirst(
        index: Int,
    ): List<Int> {
        if (
            size < 2 ||
            first() != index
        ) {
            return this
        }

        return toMutableList()
            .apply {
                val replacement =
                    indexOfFirst {
                        it != index
                    }

                this[0] =
                    this[replacement]

                this[replacement] =
                    index
            }
    }
}
