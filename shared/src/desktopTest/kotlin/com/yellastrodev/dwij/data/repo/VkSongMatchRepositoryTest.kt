package com.yellastrodev.dwij.data.repo

import androidx.room.Room
import com.yellastrodev.dwij.data.db.DwijDatabase
import com.yellastrodev.dwij.data.db.buildDwijDatabase
import com.yellastrodev.dwij.data.entities.MusicSource
import com.yellastrodev.dwij.data.entities.SongEntity
import com.yellastrodev.dwij.data.entities.SongMatchCandidateStatus
import com.yellastrodev.dwij.data.entities.TrackInstanceEntity
import com.yellastrodev.dwij.data.entities.VkLibraryEntity
import com.yellastrodev.dwij.data.entities.dYaArtist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.vkmusicsdk.VkAudio
import com.yellastrodev.yamusicsdk.NoOpYamLogger
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Проверяет VK-пары, повторный скан, пользовательский отказ и объединение через настоящий Room. */
class VkSongMatchRepositoryTest {
    /** Версия 2 пересканирует старые записи, а REJECTED остаётся постоянным решением пользователя. */
    @Test
    fun findsCrossSourcePairsAndPreservesRejection() = runBlocking {
        withDatabase { db ->
            val dao = db.songDao()
            link(db, "vk", MusicSource.VK)
            link(db, "vk-copy", MusicSource.VK)
            link(db, "ya", MusicSource.YANDEX)
            link(db, "local", MusicSource.LOCAL)
            listOf("vk", "vk-copy", "ya", "local").forEach { dao.markResolverVersion(it, 1) }
            val matches = SongMatchRepository(dao, db.songMatchDao(), NoOpYamLogger)
            matches.scanUnprocessedSongs()
            assertEquals(5, db.songMatchDao().getPendingCandidateCount())
            assertNull(db.songMatchDao().getCandidate("vk", "vk-copy"))
            assertEquals(2, db.songMatchDao().getCandidate("local", "vk")?.resolverVersion)
            matches.rejectCandidate("vk", "ya")
            dao.markResolverVersion("vk", 0)
            matches.scanUnprocessedSongs()
            assertEquals(SongMatchCandidateStatus.REJECTED.name,
                db.songMatchDao().getCandidate("vk", "ya")?.status)
            assertEquals(4, db.songMatchDao().getPendingCandidateCount())
        }
    }

    /** VK дополняет уже объединённую группу, но пересекающиеся источники не предлагаются как новая пара. */
    @Test
    fun addsVkToExistingGroup() = runBlocking {
        withDatabase { db ->
            link(db, "ya", MusicSource.YANDEX)
            link(db, "local", MusicSource.LOCAL)
            link(db, "vk", MusicSource.VK)
            link(db, "other-ya", MusicSource.YANDEX)
            db.songDao().mergeInstances(listOf("ya", "local"))
            SongMatchRepository(db.songDao(), db.songMatchDao(), NoOpYamLogger).scanUnprocessedSongs()
            assertTrue(db.songMatchDao().getCandidate("vk", "ya") != null)
            assertNull(db.songMatchDao().getCandidate("other-ya", "ya"))
        }
    }

    /** Подтверждение объединяет полные source-инстансы, не изменяя VK-коллекцию и порядок очереди. */
    @Test
    fun confirmedMergeKeepsVkIdentityAndCollection() = runBlocking {
        withDatabase { db ->
            val songs = SongRepository(db.songDao(), db.songMatchDao(), db.dTrackDao(),
                db.localLibraryDao(), db.catalogDao(), db.vkLibraryDao())
            val audio = VkAudio(10, 20, artist = "Artist", title = "Song", duration = 180)
            songs.registerVkTracks(listOf(audio))
            val yandex = dYaTrack("42", "Song", available = true, durationMs = 180000).apply {
                artists = listOf(dYaArtist(id = 1, name = "Artist"))
            }
            db.dTrackDao().insert(yandex)
            songs.registerYandexTracks(listOf(yandex))
            val vkSong = songs.songsForVkTracks(listOf(audio)).single()
            val yaSong = songs.songsForYandexTracks(listOf(yandex)).single()
            val collection = VkLibraryEntity(20, myTracksJson = "[]",
                playlistTracksJson = "{\"20_1\":[\"20_10\"]}")
            db.vkLibraryDao().upsertLibrary(collection)
            val matches = SongMatchRepository(db.songDao(), db.songMatchDao(), NoOpYamLogger)
            matches.scanUnprocessedSongs()
            assertEquals(1, db.songMatchDao().getPendingCandidateCount())
            val mergedId = songs.mergeInstances(yaSong.instances + vkSong.instances)
            matches.scanUnprocessedSongs()
            val queue = songs.songsForVkTracks(listOf(audio, audio))
            assertEquals(listOf(mergedId, mergedId), queue.map { it.id })
            assertEquals(2, queue.first().instances.size)
            assertEquals(audio.fullId, queue.first().vkInstances.single().track.fullId)
            assertEquals(collection, db.vkLibraryDao().library(20))
            assertEquals(0, db.songMatchDao().getPendingCandidateCount())
        }
    }

    /** Создаёт индексную запись для проверки состава групп без зависимости от сетевых SDK. */
    private suspend fun link(db: DwijDatabase, id: String, source: MusicSource) {
        db.songDao().link(
            SongEntity(id, "song artist", "Song", "Artist", null, 180000, null, null),
            TrackInstanceEntity(id, id, source.name, id),
        )
    }

    /** Каждая проверка использует отдельный файл БД и освобождает его даже при ошибке. */
    private suspend fun withDatabase(block: suspend (DwijDatabase) -> Unit) {
        val directory = Files.createTempDirectory("dwij-vk-matches-test").toFile()
        val db = buildDwijDatabase(Room.databaseBuilder<DwijDatabase>(name = directory.resolve("test.db").absolutePath))
        try {
            block(db)
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }
}
