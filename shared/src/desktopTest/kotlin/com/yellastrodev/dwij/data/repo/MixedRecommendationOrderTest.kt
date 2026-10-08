package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.entities.*
import com.yellastrodev.dwij.playback.alternateRecommendationSongs
import kotlin.test.Test
import kotlin.test.assertEquals

/** Проверяет строгое чередование списков разного размера и удаление общих Song.id. */
class MixedRecommendationOrderTest {
    /** Длинный VK-список не образует хвост после конца плейлиста дня. */
    @Test
    fun stopsAfterLastCompletePair() {
        val entries = alternateRecommendationSongs(listOf(song("a"), song("b")),
            listOf(song("x"), song("y"), song("z")))
        assertEquals(listOf("a", "x", "b", "y"), entries.map { it.song.id })
        assertEquals(listOf(MusicSource.YANDEX, MusicSource.VK, MusicSource.YANDEX, MusicSource.VK), entries.map { it.origin })
    }

    /** Сопоставленные песни не занимают две позиции, но источники продолжают чередоваться. */
    @Test
    fun skipsDuplicatesAcrossSources() {
        val entries = alternateRecommendationSongs(listOf(song("a"), song("b"), song("a")),
            listOf(song("a"), song("x"), song("b"), song("y")))
        assertEquals(listOf("a", "x", "b", "y"), entries.map { it.song.id })
    }

    /** Один недоступный сервис не превращает смешанную очередь в обычную одноисточниковую. */
    @Test
    fun emptySourceProducesNoPairs() {
        assertEquals(emptyList(), alternateRecommendationSongs(listOf(song("a")), emptyList()))
    }

    /** Минимальная песня для проверки состава; тест не зависит от транспорта аудио. */
    private fun song(id: String) = Song(id, id, emptyList(), emptyList(), null, null,
        emptyList(), null, false, false, false)
}
