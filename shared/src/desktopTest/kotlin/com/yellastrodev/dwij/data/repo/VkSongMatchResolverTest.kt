package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.entities.SONG_ARTIST_SEPARATOR
import com.yellastrodev.dwij.data.entities.SongEntity
import com.yellastrodev.yamusicsdk.NoOpYamLogger
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Проверяет ограничения VK-кандидатов и сохранение прежнего режима ЯМ ↔ локальный файл. */
class VkSongMatchResolverTest {
    private val resolver = SongMatchResolver(NoOpYamLogger)

    /** Старый artist credit VK сопоставляется со структурированным списком в другом источнике. */
    @Test
    fun matchesCreditsAndCosmeticDifferences() {
        assertNotNull(resolver.compare(
            song("vk", "Ёлки — иголки", "Alpha feat. Beta", 180000),
            song("other", "елки иголки", "Beta${SONG_ARTIST_SEPARATOR}Alpha", 181000),
            checkRecording = true,
        ))
    }

    /** Отсутствие длительности допустимо, разница больше десяти секунд исключает кандидата. */
    @Test
    fun checksKnownDurationOnly() {
        val first = song("vk", duration = 180000)
        assertNotNull(resolver.compare(first, song("other", duration = null), checkRecording = true))
        assertNotNull(resolver.compare(first, song("other", duration = 190000), checkRecording = true))
        assertNull(resolver.compare(first, song("other", duration = 190001), checkRecording = true))
        assertNotNull(resolver.compare(first, song("other", duration = 210000)))
    }

    /** На длинном названии fuzzy score не должен скрывать короткую пометку другой версии. */
    @Test
    fun rejectsDifferentRecordingVersions() {
        val title = "A very long song title with many words in it"
        val original = song("original", title)
        listOf("Live", "Remix", "Acoustic", "Instrumental", "Karaoke", "Cover",
            "Sped up", "Slowed", "Radio edit", "Extended", "Remastered").forEach { version ->
            assertNull(resolver.compare(original, song("vk", "$title ($version)"), checkRecording = true))
        }
        assertNotNull(resolver.compare(
            song("vk", "$title (Live)"), song("other", "$title — live"), checkRecording = true,
        ))
        assertNotNull(resolver.compare(original, song("other", "$title (Live)")))
    }

    /** Пустые артисты и явно иной исполнитель не дают совпадение даже при точном названии. */
    @Test
    fun requiresArtistEvidence() {
        assertNull(resolver.compare(song("vk", artists = ""), song("other"), checkRecording = true))
        assertNull(resolver.compare(song("vk", artists = "Someone else"), song("other"), checkRecording = true))
    }

    /** Создаёт только постоянную метадату, без SDK, URL и сетевых вызовов. */
    private fun song(
        id: String,
        title: String = "Song",
        artists: String = "Artist",
        duration: Long? = 180000,
    ) = SongEntity(id, title, title, artists, null, duration, null, null)
}
