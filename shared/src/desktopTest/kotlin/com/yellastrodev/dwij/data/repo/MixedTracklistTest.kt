package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.entities.*
import com.yellastrodev.dwij.playback.DailyPlaylistTracklist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Проверяет независимость происхождения от аудиоэкземпляров и запрет rotor для внутренних очередей. */
class MixedTracklistTest {
    /** Происхождение остаётся у песни при перестановке; один Song может иметь несколько экземпляров. */
    @Test
    fun originsAreBoundToSongsRatherThanPositions() {
        val first = song("first")
        val second = song("second")
        val list = MixedTracklist("queue", "Подборка", listOf(
            MixedTracklistEntry(second, MusicSource.VK),
            MixedTracklistEntry(first, MusicSource.YANDEX),
        ))
        assertEquals(MusicSource.YANDEX, list.originOf(first.id))
        assertEquals(MusicSource.VK, list.originOf(second.id))
        assertNull(list.originOf("other"))
        assertNull(list.yandexWaveSeed())
    }

    /** Один Song нельзя записать с неоднозначным происхождением в две позиции смешанной подборки. */
    @Test
    fun duplicateSongsRequireResolutionBeforeQueueCreation() {
        val track = song("same")
        assertFailsWith<IllegalArgumentException> {
            MixedTracklist("queue", "Подборка", listOf(
                MixedTracklistEntry(track, MusicSource.VK), MixedTracklistEntry(track, MusicSource.YANDEX)))
        }
    }

    /** Новые и конечные списки по умолчанию не отправляются в ЯМ rotor. */
    @Test
    fun finiteListsHaveNoImplicitYandexSeed() {
        assertNull(dSimpleTracklist().yandexWaveSeed())
        assertNull(VkSearchTracklist().yandexWaveSeed())
        assertNull(LocalTracklist("local", "Локальное").yandexWaveSeed())
        assertNull(DailyPlaylistTracklist("День").yandexWaveSeed())
    }

    /** Минимальная логическая песня; происхождение не выводится из экземпляров. */
    private fun song(id: String) = Song(id, id, emptyList(), emptyList(), null, null,
        emptyList(), null, false, false, false)
}
