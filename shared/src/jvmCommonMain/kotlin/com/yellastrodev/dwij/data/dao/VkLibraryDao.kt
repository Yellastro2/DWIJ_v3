package com.yellastrodev.dwij.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.yellastrodev.dwij.data.entities.VkLibraryEntity
import com.yellastrodev.dwij.data.entities.VkTrackEntity
import kotlinx.coroutines.flow.Flow

/** Source-хранилище VK: независимая метадата и один снимок коллекций на аккаунт. */
@Dao
interface VkLibraryDao {
    /** Пакетно обновляет метадату без изменения фонотеки или лайков. */
    @Upsert suspend fun upsertTracks(tracks: List<VkTrackEntity>)

    /** Пакетно читает source-записи для сборки общих Song. */
    @Query("SELECT * FROM vk_tracks WHERE fullId IN (:ids)")
    suspend fun tracks(ids: List<String>): List<VkTrackEntity>

    /** Находит метадату, запись которой успела завершиться до сбоя индексации. */
    @Query("SELECT * FROM vk_tracks WHERE fullId NOT IN (SELECT sourceTrackId FROM track_instances WHERE source = 'VK')")
    suspend fun unindexedTracks(): List<VkTrackEntity>

    /** Инвалидирует общую сборку при обновлении метадаты уже известного VK-инстанса. */
    @Query("SELECT COUNT(*) FROM vk_tracks")
    fun observeTrackChanges(): Flow<Int>

    /** Читает состояния только выбранного аккаунта. */
    @Query("SELECT * FROM vk_library WHERE accountId = :accountId")
    suspend fun library(accountId: Long): VkLibraryEntity?

    /** Атомарно заменяет снимок после успешной операции VK; остальные аккаунты не затрагивает. */
    @Upsert suspend fun upsertLibrary(library: VkLibraryEntity)
}
