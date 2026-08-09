package com.yellastrodev.dwij.data.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Junction
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import com.yellastrodev.dwij.data.entities.dTrackAlbumCrossRef
import com.yellastrodev.dwij.data.entities.dTrackArtistCrossRef
import com.yellastrodev.dwij.data.entities.dYaAlbum
import com.yellastrodev.dwij.data.entities.dYaArtist
import com.yellastrodev.dwij.data.entities.dYaTrack
import kotlin.collections.forEach

/** Пакетно загружаемый Room-снимок трека с данными, нужными спискам и плееру. */
data class YandexTrackWithRelations(
    @Embedded
    val track: dYaTrack,
    @Relation(
        parentColumn = "id",
        entityColumn = "localId",
        associateBy = Junction(
            value = dTrackArtistCrossRef::class,
            parentColumn = "trackId",
            entityColumn = "artistLocalId",
        ),
    )
    val artists: List<dYaArtist>,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = dTrackAlbumCrossRef::class,
            parentColumn = "trackId",
            entityColumn = "albumId",
        ),
    )
    val albums: List<dYaAlbum>,
) {
    fun hydrate(): dYaTrack = track.apply {
        artists = this@YandexTrackWithRelations.artists
        albums = this@YandexTrackWithRelations.albums
    }
}

@Dao
interface dTrackDao {


    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrack(track: dYaTrack)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlbum(album: dYaAlbum)

    @Query("SELECT * FROM artists WHERE id IS NULL AND name = :name LIMIT 1")
    suspend fun findLocalArtistByName(name: String): dYaArtist?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArtistInternal(artist: dYaArtist): Long

    @Query("SELECT * FROM artists WHERE id = :id LIMIT 1")
    suspend fun findArtistById(id: Int): dYaArtist?

    @Transaction
    suspend fun insertArtist(artist: dYaArtist): Long {
        // Если есть внешний id — проверяем по нему
        if (artist.id != null) {
            val existingById = findArtistById(artist.id)
            if (existingById != null) {
                return existingById.localId // уже есть — возвращаем существующий PK
            }
        } else {
            // Если id == null, проверяем по имени
            val existingByName = findLocalArtistByName(artist.name)
            if (existingByName != null) {
                return existingByName.localId // уже есть — возвращаем существующий PK
            }
        }

        // Если не нашли — вставляем
        return insertArtistInternal(artist)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrackAlbumCrossRef(ref: dTrackAlbumCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlbums(albums: List<dYaAlbum>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrackAlbumCrossRefs(refs: List<dTrackAlbumCrossRef>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrackArtistCrossRef(ref: dTrackArtistCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrackArtistCrossRefs(refs: List<dTrackArtistCrossRef>)

    @Transaction
    suspend fun insert(
        track: dYaTrack
    ) {
        insertTrack(track)
        updateTracks(listOf(track))
        track.albums.forEach {
            insertAlbum(it)
            insertTrackAlbumCrossRef(dTrackAlbumCrossRef(track.id, it.id))
        }
        track.artists.forEach {
            val localId = insertArtist(it)
            insertTrackArtistCrossRef(dTrackArtistCrossRef(track.id, localId))
        }
    }

    @Query("SELECT id FROM tracks WHERE id IN (:ids)")
    suspend fun getExistingTrackIds(ids: List<String>): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun _insertTracks(tracks: List<dYaTrack>)

    @Update
    suspend fun updateTracks(tracks: List<dYaTrack>)

    /**
     * Добавляет новые треки и обновляет существующие без REPLACE, чтобы не удалить
     * каскадно их связи с плейлистами, альбомами и артистами.
     */
    @Transaction
    suspend fun insertAll(tracks: List<dYaTrack>) {
        if (tracks.isEmpty()) return

        // 1. Определяем, какие треки уже есть
        val existingIds = getExistingTrackIds(tracks.map { it.id }).toSet()
        val newTracks = tracks.filter { it.id !in existingIds }
        val existingTracks = tracks.filter { it.id in existingIds }

        // 2. Вставляем только новые треки
        _insertTracks(newTracks)
        if (existingTracks.isNotEmpty()) {
            updateTracks(existingTracks)
        }

        // 3. Собираем все альбомы и связи
        val allAlbums = tracks.flatMap { it.albums }.distinctBy { it.id }
        insertAlbums(allAlbums)

        val albumRefs = tracks.flatMap { track ->
            track.albums.map { album -> dTrackAlbumCrossRef(track.id, album.id) }
        }
        insertTrackAlbumCrossRefs(albumRefs)

        // 4. Артисты: вставляем по одному, чтобы сохранить insertArtist-логику
        val artistRefs = mutableListOf<dTrackArtistCrossRef>()
        for (track in tracks) {
            for (artist in track.artists) {
                val localId = insertArtist(artist) // твоя логика с поиском по имени
                artistRefs.add(dTrackArtistCrossRef(track.id, localId))
            }
        }
        insertTrackArtistCrossRefs(artistRefs)
    }

    @Transaction
    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun getTrackWithRelations(id: String): YandexTrackWithRelations?

    suspend fun getTrack(id: String): dYaTrack? =
        getTrackWithRelations(id)?.hydrate()

    @Transaction
    @Query("SELECT * FROM tracks")
    suspend fun getAllTracksWithRelations(): List<YandexTrackWithRelations>

    suspend fun getAllTracks(): List<dYaTrack> =
        getAllTracksWithRelations().map(YandexTrackWithRelations::hydrate)

    @Transaction
    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getTracksWithRelations(ids: List<String>): List<YandexTrackWithRelations>

    suspend fun getTracks(ids: List<String>): List<dYaTrack> {
        if (ids.isEmpty()) return emptyList()
        return getTracksWithRelations(ids).map(YandexTrackWithRelations::hydrate)
    }



    @Query("DELETE FROM tracks WHERE id = :string")
    suspend fun delete(string: String)
}
