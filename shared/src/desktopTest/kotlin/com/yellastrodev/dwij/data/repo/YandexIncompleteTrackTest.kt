package com.yellastrodev.dwij.data.repo

import androidx.room.Room
import com.yellastrodev.dwij.data.db.DwijDatabase
import com.yellastrodev.dwij.data.db.buildDwijDatabase
import com.yellastrodev.dwij.data.entities.dPlaylistTrack
import com.yellastrodev.dwij.data.entities.dYaArtist
import com.yellastrodev.dwij.data.entities.dYaLikeTracklist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.entities.toEntity
import com.yellastrodev.yamusicsdk.entities.YaTrack
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Проверяет неполные ответы и лайки без метаданных на настоящих таблицах Room. */
class YandexIncompleteTrackTest {
    /** Неполная запись сохраняет старую метадату и связи, а полный ответ позднее восстанавливает доступность. */
    @Test
    fun incompleteTrackPreservesStoredMetadata() = runBlocking {
        val directory = Files.createTempDirectory("dwij-incomplete-track").toFile()
        val db = buildDwijDatabase(Room.databaseBuilder<DwijDatabase>(name = directory.resolve("test.db").absolutePath))
        try {
            val dao = db.dTrackDao()
            val old = dYaTrack("10", "Сохранённое название", true, durationMs = 120000, coverUri = "cover").apply {
                artists = listOf(dYaArtist(id = 1, name = "Артист"))
            }
            dao.insertAll(listOf(old))
            val playlist = dYaLikeTracklist("liked", 1, 1, 1, 0).apply {
                tracks = listOf(dPlaylistTrack("liked", "10", 0))
            }
            db.dPlaylistDao().insert(playlist)
            dao.insertAll(listOf(YaTrack(id = "10").toEntity(42)))
            val stored = requireNotNull(dao.getTrack("10"))
            assertEquals(old.title, stored.title)
            assertEquals(old.durationMs, stored.durationMs)
            assertEquals(old.coverUri, stored.coverUri)
            assertEquals("Артист", stored.artists.single().name)
            assertEquals(42L, stored.availabilityCheckedAt)
            assertFalse(stored.available)
            assertTrue(db.dPlaylistDao().isTrackLiked("10"))
            dao.insertAll(listOf(YaTrack("10", "Вернувшийся трек", true).toEntity(43)))
            assertTrue(requireNotNull(dao.getTrack("10")).available)
            assertEquals("Вернувшийся трек", requireNotNull(dao.getTrack("10")).title)
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }

    /** Лайк сохраняется без загрузки трека и без постоянной Song; снятие лайка тоже определяется по source ID. */
    @Test
    fun likedMembershipDoesNotRequireTrackMetadata() = runBlocking {
        val directory = Files.createTempDirectory("dwij-liked-source-id").toFile()
        val db = buildDwijDatabase(Room.databaseBuilder<DwijDatabase>(name = directory.resolve("test.db").absolutePath))
        try {
            val playlist = dYaLikeTracklist("liked", 1, 1, 1, 0).apply {
                tracks = listOf(dPlaylistTrack("liked", "10", 0))
            }
            db.dPlaylistDao().insert(playlist)
            assertNull(db.dTrackDao().getTrack("10"))
            assertNull(db.songDao().getSong("daily:song:10"))
            assertTrue(db.dPlaylistDao().isTrackLiked("10"))
            db.dPlaylistDao().insert(dYaLikeTracklist("liked", 1, 2, 0, 0))
            assertFalse(db.dPlaylistDao().isTrackLiked("10"))
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }
}
