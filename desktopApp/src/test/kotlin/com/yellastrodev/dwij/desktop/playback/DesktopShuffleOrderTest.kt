package com.yellastrodev.dwij.desktop.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/** Проверяет порядок shuffle без запуска JavaFX-аудиобэкенда. */
class DesktopShuffleOrderTest {

    @Test
    fun cycleContainsEveryTrackOnce() {
        val order =
            shuffleOrder(
                queueSize =
                    8,
                currentIndex =
                    3,
            )

        val played =
            buildList {
                add(
                    3,
                )

                while (true) {
                    val next =
                        order.next(
                            repeatAll =
                                false,
                        ) ?: break

                    add(
                        next,
                    )
                }
            }

        assertEquals(
            (0 until 8).toSet(),
            played.toSet(),
        )
        assertEquals(
            8,
            played.size,
        )
        assertNull(
            order.next(
                repeatAll =
                    false,
            ),
        )
    }

    @Test
    fun peekNextDoesNotMoveCurrentPosition() {
        val order =
            shuffleOrder(
                queueSize =
                    6,
                currentIndex =
                    0,
            )

        val upcoming =
            order.peekNext(
                count =
                    2,
            )

        assertEquals(
            2,
            upcoming.size,
        )
        assertEquals(
            upcoming.first(),
            order.next(
                repeatAll =
                    false,
            ),
        )
        assertEquals(
            upcoming.drop(1),
            order.peekNext(
                count =
                    1,
            ),
        )
    }

    @Test
    fun previousThenNextRestoresHistory() {
        val order =
            shuffleOrder()

        val firstNext =
            order.next(
                repeatAll =
                    false,
            )

        val secondNext =
            order.next(
                repeatAll =
                    false,
            )

        assertEquals(
            firstNext,
            order.previous(),
        )
        assertEquals(
            secondNext,
            order.next(
                repeatAll =
                    false,
            ),
        )
    }

    @Test
    fun enablingShuffleStartsWithCurrentTrack() {
        val order =
            shuffleOrder(
                queueSize =
                    5,
                currentIndex =
                    2,
            )

        assertEquals(
            2,
            order.previous(),
        )
        assertFalse(
            order.next(
                repeatAll =
                    false,
            ) == 2,
        )
    }

    @Test
    fun directSelectionKeepsHistoryAndRemovesTrackFromFuture() {
        val probe =
            shuffleOrder(
                queueSize =
                    6,
                currentIndex =
                    0,
            )

        probe.next(
            repeatAll =
                false,
        )

        val future =
            generateSequence {
                probe.next(
                    repeatAll =
                        false,
                )
            }.toList()

        val selected =
            future.last()

        val order =
            shuffleOrder(
                queueSize =
                    6,
                currentIndex =
                    0,
            )

        val historyTrack =
            order.next(
                repeatAll =
                    false,
            )!!

        order.select(
            selected,
        )

        assertEquals(
            historyTrack,
            order.previous(),
        )
        assertEquals(
            selected,
            order.next(
                repeatAll =
                    false,
            ),
        )

        val remaining =
            generateSequence {
                order.next(
                    repeatAll =
                        false,
                )
            }.toList()

        assertFalse(
            selected in remaining,
        )
    }

    @Test
    fun appendedTracksAreAddedOnlyAfterCurrentPosition() {
        val order =
            shuffleOrder(
                queueSize =
                    4,
                currentIndex =
                    1,
            )

        val previous =
            order.next(
                repeatAll =
                    false,
            )!!

        order.append(
            previousQueueSize =
                4,
            newQueueSize =
                7,
        )

        assertEquals(
            1,
            order.previous(),
        )
        assertEquals(
            previous,
            order.next(
                repeatAll =
                    false,
            ),
        )

        val future =
            generateSequence {
                order.next(
                    repeatAll =
                        false,
                )
            }.toList()

        assertEquals(
            setOf(
                4,
                5,
                6,
            ),
            future.filter {
                it >= 4
            }.toSet(),
        )
    }

    @Test
    fun selectionInitializesOrderAfterAppendingToEmptyQueue() {
        val order =
            DesktopShuffleOrder(
                random =
                    Random(
                        42,
                    ),
            )

        order.append(
            previousQueueSize =
                0,
            newQueueSize =
                4,
        )
        order.select(
            2,
        )

        assertEquals(
            2,
            order.previous(),
        )

        val future =
            generateSequence {
                order.next(
                    repeatAll =
                        false,
                )
            }.toList()

        assertEquals(
            setOf(
                0,
                1,
                3,
            ),
            future.toSet(),
        )
    }

    @Test
    fun resetReplacesPreviousQueue() {
        val order =
            shuffleOrder(
                queueSize =
                    7,
                currentIndex =
                    6,
            )

        order.reset(
            queueSize =
                3,
            currentIndex =
                1,
        )

        val cycle =
            buildList {
                add(
                    1,
                )

                while (true) {
                    val next =
                        order.next(
                            repeatAll =
                                false,
                        ) ?: break

                    add(
                        next,
                    )
                }
            }

        assertEquals(
            setOf(
                0,
                1,
                2,
            ),
            cycle.toSet(),
        )
    }

    @Test
    fun repeatAllStartsNewCycleWithoutImmediateRepeat() {
        val order =
            shuffleOrder(
                queueSize =
                    5,
                currentIndex =
                    0,
            )

        val firstCycle =
            buildList {
                add(
                    0,
                )

                repeat(4) {
                    add(
                        order.next(
                            repeatAll =
                                false,
                        )!!,
                    )
                }
            }

        val firstOfNewCycle =
            order.next(
                repeatAll =
                    true,
            )!!

        assertFalse(
            firstCycle.last() == firstOfNewCycle,
        )

        val secondCycle =
            buildList {
                add(
                    firstOfNewCycle,
                )

                repeat(4) {
                    add(
                        order.next(
                            repeatAll =
                                false,
                        )!!,
                    )
                }
            }

        assertEquals(
            (0 until 5).toSet(),
            secondCycle.toSet(),
        )

        var previousAtBoundary: Int? =
            null

        repeat(5) {
            previousAtBoundary =
                order.previous()
        }

        assertEquals(
            firstCycle.last(),
            previousAtBoundary,
        )
        assertEquals(
            firstOfNewCycle,
            order.next(
                repeatAll =
                    true,
            ),
        )
    }

    @Test
    fun singleTrackHonorsRepeatMode() {
        val order =
            shuffleOrder(
                queueSize =
                    1,
                currentIndex =
                    0,
            )

        assertNull(
            order.next(
                repeatAll =
                    false,
            ),
        )
        assertEquals(
            0,
            order.next(
                repeatAll =
                    true,
            ),
        )
        assertEquals(
            0,
            order.previous(),
        )
    }

    private fun shuffleOrder(
        queueSize: Int = 5,
        currentIndex: Int = 0,
    ) =
        DesktopShuffleOrder(
            random =
                Random(
                    42,
                ),
        ).apply {
            reset(
                queueSize =
                    queueSize,
                currentIndex =
                    currentIndex,
            )
        }
}
