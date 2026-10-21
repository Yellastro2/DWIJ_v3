package com.yellastrodev.dwij.models

import com.yellastrodev.dwij.data.entities.MusicSource
import com.yellastrodev.dwij.data.entities.Song
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Проверяет полноту общей фонотеки и устойчивость случайного порядка при обновлениях. */
class LibraryShuffleOrderTest {
    /** Новый маршрут может иметь другой случайный порядок при неизменном составе фонотеки. */
    @Test
    fun differentRandomSeedsProduceDifferentOrders() {
        val songs = (1..20).map { song("local$it") }
        val first = LibraryShuffleOrder(Random(1)).update(songs, emptyList(), emptyList())
        val second = LibraryShuffleOrder(Random(2)).update(songs, emptyList(), emptyList())
        assertNotEquals(first.map { it.song.id }, second.map { it.song.id })
        assertEquals(first.map { it.song.id }.toSet(), second.map { it.song.id }.toSet())
    }

    /** Длинный источник не обрезается, а уже связанные песни занимают одну строку. */
    @Test
    fun combinesAllSourcesWithoutTruncatingOrDuplicatingSongs() {
        val entries = LibraryShuffleOrder(Random(42)).update(
            local = listOf(song("local"), song("shared")),
            yandex = listOf(song("yandex"), song("shared")),
            vk = listOf(song("vk1"), song("vk2"), song("vk3")),
        )
        assertEquals(setOf("local", "shared", "yandex", "vk1", "vk2", "vk3"), entries.map { it.song.id }.toSet())
        assertEquals(6, entries.size)
        assertEquals(MusicSource.LOCAL, entries.single { it.song.id == "shared" }.origin)
    }

    /** Изменение порядка источника и метаданных не тасует уже показанные строки. */
    @Test
    fun metadataUpdatesKeepOrderAndPublishNewSongData() {
        val order = LibraryShuffleOrder(Random(7))
        val first = order.update(emptyList(), listOf(song("a"), song("b"), song("c")), emptyList())
        val updated = order.update(emptyList(), listOf(song("c"), song("a").copy(title = "Новое название"), song("b")), emptyList())
        assertEquals(first.map { it.song.id }, updated.map { it.song.id })
        assertEquals("Новое название", updated.single { it.song.id == "a" }.song.title)
    }

    /** Медленный источник дополняет список, сохраняя взаимный порядок ранее показанных песен. */
    @Test
    fun lateSourceDoesNotReshuffleExistingSongs() {
        val order = LibraryShuffleOrder(Random(11))
        val vk = (1..20).map { song("vk$it") }
        val first = order.update(emptyList(), emptyList(), vk)
        val loaded = order.update(listOf(song("local")), listOf(song("yandex")), vk)
        assertEquals(first.map { it.song.id }, loaded.filter { it.origin == MusicSource.VK }.map { it.song.id })
        assertEquals(22, loaded.size)
    }

    /** Исчезнувшие песни не остаются в общей фонотеке; пустые источники не блокируют доступный. */
    @Test
    fun removesMissingSongsAndSupportsSingleSource() {
        val order = LibraryShuffleOrder(Random(3))
        order.update(listOf(song("old")), listOf(song("yandex")), listOf(song("vk")))
        val remaining = order.update(emptyList(), listOf(song("yandex")), emptyList())
        assertEquals(listOf("yandex"), remaining.map { it.song.id })
        assertEquals(emptyList(), order.update(emptyList(), emptyList(), emptyList()))
    }

    /** Минимальная каноническая песня без сетевых и файловых зависимостей. */
    private fun song(id: String) = Song(id, id, emptyList(), emptyList(), null, null,
        emptyList(), null, false, false, false)
}
