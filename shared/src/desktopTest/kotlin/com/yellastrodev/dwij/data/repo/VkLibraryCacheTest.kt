package com.yellastrodev.dwij.data.repo

import androidx.room.Room
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.db.DwijDatabase
import com.yellastrodev.dwij.data.db.buildDwijDatabase
import com.yellastrodev.dwij.data.entities.VkLibraryEntity
import com.yellastrodev.dwij.data.entities.VkTrackEntity
import com.yellastrodev.dwij.storage.ProtectedSessionPayloadStore
import com.yellastrodev.vkmusicsdk.VkAudio
import com.yellastrodev.vkmusicsdk.VkPlaylist
import com.yellastrodev.yamusicsdk.NoOpYamLogger
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Проверяет восстановление сетки и порядка из Room без сети, с привязкой к защищённой сессии. */
class VkLibraryCacheTest {
    /** Новый экземпляр репозитория открывает кеш с дублями; снимок не объявляется свежим статусом лайков. */
    @Test
    fun restoresAccountGridAndOrderedContentWithoutNetwork() = runBlocking {
        withDatabase { db ->
            val first = VkAudio(10, 20, title = "First")
            val second = VkAudio(11, 20, title = "Second")
            db.vkLibraryDao().upsertTracks(listOf(first, second).map { VkTrackEntity.from(it) })
            val playlist = VkPlaylist(1, 20, title = "List", count = 3)
            db.vkLibraryDao().upsertLibrary(VkLibraryEntity(20, myTracksJson = "[]",
                playlistTracksJson = "{\"20_1\":[\"20_11\",\"20_10\",\"20_11\"]}",
                playlistsJson = Json.encodeToString(listOf(playlist))))
            val repository = repository(db, 20)
            try {
                repository.restore()
                assertTrue(repository.authorized.value)
                assertTrue(repository.hasCachedPlaylists())
                assertEquals(listOf(playlist), repository.playlists.value)
                val content = assertNotNull(repository.cachedPlaylist("20_1"))
                assertEquals(listOf(second.fullId, first.fullId, second.fullId), content.tracks.map { it.fullId })
                assertIs<DataResult.Success<VkPlaylistContent>>(repository.getPlaylist("20_1", preferLocal = true))
                assertEquals(emptyList(), repository.cachedPlaylist(VK_MY_TRACKS)?.tracks)
                assertFalse(repository.myTracksLoaded.value)
                assertEquals(2, repository.cachedPlaylist(VK_ALL_TRACKS)?.tracks?.size)
            } finally { repository.close() }
        }
    }

    /** Кеш другого аккаунта не показывается; известная пустая сетка отличается от отсутствующего снимка. */
    @Test
    fun isolatesAccountsAndRecognizesEmptyGrid() = runBlocking {
        withDatabase { db ->
            db.vkLibraryDao().upsertLibrary(VkLibraryEntity(20, playlistsJson = Json.encodeToString(
                listOf(VkPlaylist(1, 20, title = "Private list")))))
            db.vkLibraryDao().upsertLibrary(VkLibraryEntity(30, playlistsJson = "[]"))
            val empty = repository(db, 30)
            val unknown = repository(db, 40)
            val legacy = VkMusicRepository(MemoryStore("synthetic-token"), NoOpYamLogger,
                songs(db), db.vkLibraryDao())
            try {
                empty.restore()
                assertTrue(empty.hasCachedPlaylists())
                assertTrue(empty.playlists.value.isEmpty())
                assertNull(empty.cachedPlaylist("20_1"))
                unknown.restore()
                assertFalse(unknown.hasCachedPlaylists())
                legacy.restore()
                assertTrue(legacy.authorized.value)
                assertNull(legacy.userId.value)
                assertTrue(legacy.playlists.value.isEmpty())
            } finally { empty.close(); unknown.close(); legacy.close() }
        }
    }

    /** Полный виртуальный список недоступен из частичного снимка, обычный пустой плейлист доступен. */
    @Test
    fun distinguishesEmptyContentFromMissingContent() = runBlocking {
        withDatabase { db ->
            db.vkLibraryDao().upsertLibrary(VkLibraryEntity(20, myTracksJson = "[]",
                playlistTracksJson = "{\"20_1\":[]}", playlistsJson = Json.encodeToString(listOf(
                    VkPlaylist(1, 20, title = "Empty"), VkPlaylist(2, 20, title = "Unknown")))))
            val repository = repository(db, 20)
            try {
                repository.restore()
                assertTrue(assertNotNull(repository.cachedPlaylist("20_1")).tracks.isEmpty())
                assertNull(repository.cachedPlaylist("20_2"))
                assertNull(repository.cachedPlaylist(VK_ALL_TRACKS))
            } finally { repository.close() }
        }
    }

    /** Использует синтетическую сессию; восстановление и чтение кеша не вызывают VK API. */
    private fun repository(db: DwijDatabase, owner: Long) = VkMusicRepository(
        MemoryStore("{\"token\":\"synthetic-token\",\"accountId\":$owner}"),
        NoOpYamLogger, songs(db), db.vkLibraryDao(),
    )

    /** Повторяет зависимости общей сборки Song. */
    private fun songs(db: DwijDatabase) = SongRepository(db.songDao(), db.songMatchDao(),
        db.dTrackDao(), db.localLibraryDao(), db.catalogDao(), db.vkLibraryDao())

    /** Изолирует реальные Room-таблицы каждой проверки. */
    private suspend fun withDatabase(block: suspend (DwijDatabase) -> Unit) {
        val directory = Files.createTempDirectory("dwij-vk-cache-test").toFile()
        val db = buildDwijDatabase(Room.databaseBuilder<DwijDatabase>(name = directory.resolve("test.db").absolutePath))
        try { block(db) } finally { db.close(); directory.deleteRecursively() }
    }

    /** Подменяет только защищённый диск; токен синтетический и остаётся в памяти теста. */
    private class MemoryStore(payload: String) : ProtectedSessionPayloadStore {
        private var value: ByteArray? = payload.toByteArray(Charsets.UTF_8)
        /** Возвращает копию текущего payload. */
        override fun read(): ByteArray? = value?.copyOf()
        /** Сохраняет копию без файлового ввода-вывода. */
        override fun write(payload: ByteArray) { value = payload.copyOf() }
        /** Удаляет тестовую сессию. */
        override fun clear() { value = null }
    }
}
