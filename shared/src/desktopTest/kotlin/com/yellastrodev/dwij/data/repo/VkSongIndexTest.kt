package com.yellastrodev.dwij.data.repo

import androidx.room.Room
import com.yellastrodev.dwij.data.db.DwijDatabase
import com.yellastrodev.dwij.data.db.buildDwijDatabase
import com.yellastrodev.dwij.data.entities.VkLibraryEntity
import com.yellastrodev.vkmusicsdk.VkAudio
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Проверяет постоянную VK-идентичность, восстановление очереди и независимость коллекций в настоящем Room. */
class VkSongIndexTest {
    /** Повторная индексация и рестарт сохраняют Song ID, формат и повторяющиеся позиции очереди. */
    @Test
    fun indexSurvivesRestartWithoutPlaybackUrls() = runBlocking {
        val directory = Files.createTempDirectory("dwij-vk-index-test").toFile()
        val name = directory.resolve("test.db").absolutePath
        var db = buildDwijDatabase(Room.databaseBuilder<DwijDatabase>(name = name))
        try {
            val first = VkAudio(10, 20, artist = "Artist", title = "Song", duration = 123,
                url = "https://audio.example/song.mp3?synthetic=secret", accessKey = "synthetic-key", like = true)
            val second = VkAudio(11, 20, artist = "Artist", title = "Other", duration = 90)
            val repository = repository(db)
            repository.registerVkTracks(listOf(first, second))
            val before = repository.songsForVkTracks(listOf(first)).single()
            repository.registerVkTracks(listOf(first.copy(url = "", title = "Updated")))
            repository.registerVkTracks(listOf(first.copy(title = "Old offline snapshot")), onlyIfMissing = true)
            db.close()
            db = buildDwijDatabase(Room.databaseBuilder<DwijDatabase>(name = name))
            val queue = repository(db).songsForVkTracks(listOf(second, first, first))
            assertEquals(3, queue.size)
            assertEquals(before.id, queue[1].id)
            assertEquals(queue[1].id, queue[2].id)
            assertEquals("Updated", queue[1].title)
            assertEquals(123000L, queue[1].durationMs)
            assertFalse(queue[1].vkInstances.single().isHls)
            assertEquals("", queue[1].vkInstances.single().track.url)
            assertNull(queue[1].vkInstances.single().track.accessKey)
            assertFalse(queue[1].isLiked)
            assertNull(db.vkLibraryDao().library(20)) // Индекс не означает добавление в коллекцию.
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }

    /** Членство в плейлисте, «Мои треки» и общий лайк не подменяют друг друга или другой аккаунт. */
    @Test
    fun sourceCollectionsRemainIndependent() = runBlocking {
        val directory = Files.createTempDirectory("dwij-vk-collections-test").toFile()
        val db = buildDwijDatabase(Room.databaseBuilder<DwijDatabase>(name = directory.resolve("test.db").absolutePath))
        try {
            val audio = VkAudio(10, 20, title = "Song")
            repository(db).registerVkTracks(listOf(audio))
            db.vkLibraryDao().upsertLibrary(VkLibraryEntity(20, myTracksJson = "[]",
                playlistTracksJson = "{\"20_1\":[\"20_10\"]}"))
            db.vkLibraryDao().upsertLibrary(VkLibraryEntity(30, myTracksJson = "[\"30_99\"]",
                aliasesJson = "{\"20_10\":\"30_99\"}"))
            val first = requireNotNull(db.vkLibraryDao().library(20))
            val second = requireNotNull(db.vkLibraryDao().library(30))
            assertTrue(requireNotNull(first.myTrackIds()).isEmpty())
            assertEquals(listOf("20_10"), first.playlistTracks()["20_1"])
            assertTrue(first.aliases().isEmpty())
            assertEquals("30_99", second.aliases()["20_10"])
            assertFalse(repository(db).songsForVkTracks(listOf(audio)).single().isLiked)
            assertNull(VkLibraryEntity(40).myTrackIds()) // «Не загружено» отличается от пустого списка.
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }

    /** Использует тот же набор DAO, что и граф приложения. */
    private fun repository(db: DwijDatabase) = SongRepository(
        db.songDao(), db.songMatchDao(), db.dTrackDao(), db.localLibraryDao(), db.catalogDao(), db.vkLibraryDao(),
    )
}
