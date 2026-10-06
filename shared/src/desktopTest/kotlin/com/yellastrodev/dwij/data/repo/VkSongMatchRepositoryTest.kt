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
import com.yellastrodev.yamusicsdk.YamLogger
import com.yellastrodev.dwij.storage.LocalKeyValueStore
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Проверяет VK-пары, пользовательские решения и остановку/возобновление скана через настоящий Room. */
class VkSongMatchRepositoryTest {
    /** Отсутствующий ключ включает скан; сохранённое отключение переживает создание репозитория и оставляет очередь. */
    @Test
    fun persistsScanSettingAndResumesUnprocessedSongs() = runBlocking {
        withDatabase { db ->
            link(db, "vk", MusicSource.VK)
            link(db, "ya", MusicSource.YANDEX)
            val settings = BooleanSettingsStore()
            val matches = SongMatchRepository(db.songDao(), db.songMatchDao(), NoOpYamLogger, settings)
            assertTrue(matches.scanEnabled.value)
            matches.setScanEnabled(false)
            val restored = SongMatchRepository(db.songDao(), db.songMatchDao(), NoOpYamLogger, settings)
            assertEquals(false, restored.scanEnabled.value)
            restored.scanUnprocessedSongs()
            assertEquals(2, db.songDao().getUnscannedSongs(SongMatchRepository.CURRENT_RESOLVER_VERSION, 32).size)
            assertEquals(0, db.songMatchDao().getPendingCandidateCount())
            restored.setScanEnabled(true)
            restored.scanUnprocessedSongs()
            assertEquals(1, db.songMatchDao().getPendingCandidateCount())
            assertTrue(db.songDao().getUnscannedSongs(SongMatchRepository.CURRENT_RESOLVER_VERSION, 32).isEmpty())
            restored.setScanEnabled(false)
            restored.scanUnprocessedSongs()
            assertEquals(1, db.songMatchDao().getPendingCandidateCount())
        }
    }

    /** Отключение во время CPU-сравнения не помечает прерванную песню обработанной и не продолжает сравнения. */
    @Test
    fun stopsInsideCandidateComparison() = runBlocking {
        withDatabase { db ->
            link(db, "a-vk", MusicSource.VK)
            link(db, "b-ya", MusicSource.YANDEX)
            link(db, "c-local", MusicSource.LOCAL)
            lateinit var matches: SongMatchRepository
            var comparisons = 0
            val logger = object : YamLogger by NoOpYamLogger {
                /** Имитирует отключение переключателя сразу после первого сравнения. */
                override fun debug(tag: String, message: String) {
                    if (tag == "SongMatchResolver") {
                        comparisons += 1
                        matches.setScanEnabled(false)
                    }
                }
            }
            matches = SongMatchRepository(db.songDao(), db.songMatchDao(), logger)
            matches.scanUnprocessedSongs()
            assertEquals(1, comparisons)
            assertEquals(3, db.songDao().getUnscannedSongs(SongMatchRepository.CURRENT_RESOLVER_VERSION, 32).size)
            assertEquals(0, db.songMatchDao().getPendingCandidateCount())
        }
    }

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

    /** Минимальное key-value хранилище для проверки восстановления переключателя. */
    private class BooleanSettingsStore : LocalKeyValueStore {
        private val values = mutableMapOf<String, Boolean>()
        /** Строковые настройки эта проверка не использует. */
        override fun getString(key: String): String? = null
        /** Числовые настройки эта проверка не использует. */
        override fun getLong(key: String): Long? = null
        /** Возвращает сохранённое значение или отсутствие ключа. */
        override fun getBoolean(key: String): Boolean? = values[key]
        /** Применяет операции к общему состоянию, доступному следующему экземпляру репозитория. */
        override fun edit(block: LocalKeyValueStore.Editor.() -> Unit) {
            block(object : LocalKeyValueStore.Editor {
                /** Строковые записи здесь не используются. */
                override fun putString(key: String, value: String) = Unit
                /** Числовые записи здесь не используются. */
                override fun putLong(key: String, value: Long) = Unit
                /** Сохраняет булеву настройку. */
                override fun putBoolean(key: String, value: Boolean) { values[key] = value }
                /** Удаляет сохранённую настройку. */
                override fun remove(key: String) { values.remove(key) }
            })
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
