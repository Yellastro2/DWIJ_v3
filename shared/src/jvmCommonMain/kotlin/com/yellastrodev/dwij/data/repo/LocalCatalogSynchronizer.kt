package com.yellastrodev.dwij.data.repo

import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.dao.CatalogDao
import com.yellastrodev.dwij.data.dao.LocalLibraryDao
import com.yellastrodev.dwij.data.entities.CATALOG_SOURCE_YANDEX
import com.yellastrodev.dwij.data.entities.CATALOG_VALUE_SEPARATOR
import com.yellastrodev.dwij.data.entities.CatalogAlbumMetadataEntity
import com.yellastrodev.dwij.data.entities.CatalogArtistMetadataEntity
import com.yellastrodev.dwij.data.entities.LocalTrackEntity
import com.yellastrodev.dwij.data.entities.dYaAlbum
import com.yellastrodev.dwij.data.entities.dYaArtist
import com.yellastrodev.dwij.data.entities.toEntity
import com.yellastrodev.yamusicsdk.entities.YaAlbum
import com.yellastrodev.yamusicsdk.entities.YaArtist
import kotlinx.coroutines.CancellationException

/** Итог применения сетевого решения к тому же снимку локальной метадаты. */
sealed interface LocalCatalogSyncResult {
    data class Applied(val resolution: LocalCatalogResolution) : LocalCatalogSyncResult
    data object StaleLocalMetadata : LocalCatalogSyncResult
}

/**
 * Выполняет один полный цикл резолвинга, но сам нигде не планируется и не запускается.
 * Сетевая ошибка не меняет онлайн-отметку, а успешный исход применяется одной транзакцией.
 */
class LocalCatalogSynchronizer(
    private val resolver: LocalCatalogResolver,
    private val database: RoomDatabase,
    private val localDao: LocalLibraryDao,
    private val catalogDao: CatalogDao,
    private val songRepository: SongRepository,
) {
    /** Возвращает только строки с новым локальным хешем или старой версией резолвера. */
    suspend fun pendingTracks(): List<LocalTrackEntity> =
        localDao.getTracksPendingOnlineResolution(CURRENT_RESOLVER_VERSION)

    suspend fun resolve(localTrack: LocalTrackEntity): DataResult<LocalCatalogSyncResult> {
        val processedHash = localTrack.currentHash
        if (processedHash.isBlank()) {
            return DataResult.Failure(
                DataError.InvalidData(
                    "Для локального трека ${localTrack.instanceId} ещё не вычислен currentHash",
                ),
            )
        }

        val resolution = when (val result = resolver.resolve(localTrack)) {
            is DataResult.Success -> result.value
            is DataResult.Failure -> return result
        }
        return applyResolution(
            instanceId = localTrack.instanceId,
            processedHash = processedHash,
            resolution = resolution,
        )
    }

    private suspend fun applyResolution(
        instanceId: String,
        processedHash: String,
        resolution: LocalCatalogResolution,
    ): DataResult<LocalCatalogSyncResult> = try {
        database.useWriterConnection { connection ->
            connection.immediateTransaction transaction@{
                val storedTrack = localDao.getTrack(instanceId)
                    ?: return@transaction DataResult.Failure(
                        DataError.NotFound("Локальный трек", instanceId),
                    )
                if (storedTrack.currentHash != processedHash) {
                    return@transaction DataResult.Success(
                        LocalCatalogSyncResult.StaleLocalMetadata,
                    )
                }

                when (resolution) {
                    is LocalCatalogResolution.Track -> {
                        songRepository.linkResolvedLocalTrackToYandex(
                            localInstanceId = instanceId,
                            yandexTrack = resolution.track.toEntity(),
                        )
                        replaceArtists(instanceId, resolution.track.artists)
                        replaceAlbums(instanceId, resolution.track.albums)
                    }
                    is LocalCatalogResolution.Artists -> {
                        replaceArtists(instanceId, resolution.artists)
                        catalogDao.deleteTrackInstanceAlbums(instanceId)
                    }
                    is LocalCatalogResolution.NotFound,
                    is LocalCatalogResolution.Ambiguous -> {
                        catalogDao.deleteTrackInstanceArtists(instanceId)
                        catalogDao.deleteTrackInstanceAlbums(instanceId)
                    }
                }

                check(
                    localDao.markOnlineResolutionCompleted(
                        instanceId = instanceId,
                        processedHash = processedHash,
                        resolverVersion = CURRENT_RESOLVER_VERSION,
                    ) == 1,
                ) {
                    "currentHash локального трека изменился внутри транзакции"
                }
                DataResult.Success(LocalCatalogSyncResult.Applied(resolution))
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        DataResult.Failure(DataError.Storage(error))
    }

    private suspend fun replaceArtists(
        instanceId: String,
        artists: List<YaArtist>,
    ) {
        val now = System.currentTimeMillis()
        artists.filter { it.id != null }.distinctBy { it.id }.forEach { artist ->
            val externalId = requireNotNull(artist.id).toString()
            val artistId = catalogDao.ensureYandexArtist(
                yandexId = requireNotNull(artist.id),
                name = artist.name,
            )
            val stored = catalogDao.getArtistMetadata(
                artistId = artistId,
                source = CATALOG_SOURCE_YANDEX,
            )
            catalogDao.upsertArtistMetadata(
                CatalogArtistMetadataEntity(
                    artistId = artistId,
                    source = CATALOG_SOURCE_YANDEX,
                    externalId = externalId,
                    coverUri = artist.cover?.uri ?: artist.ogImageUri ?: stored?.coverUri,
                    genres = artist.genres
                        ?.takeIf { it.isNotEmpty() }
                        ?.joinToString(CATALOG_VALUE_SEPARATOR)
                        ?: stored?.genres.orEmpty(),
                    likesCount = artist.likesCount ?: stored?.likesCount,
                    trackCount = artist.counts?.tracks ?: stored?.trackCount,
                    lastMonthListeners = stored?.lastMonthListeners,
                    lastMonthListenersDelta = stored?.lastMonthListenersDelta,
                    refreshedAt = now,
                ),
            )
        }
        catalogDao.replaceTrackArtistsFromYandex(
            instanceId = instanceId,
            artists = artists.map { artist ->
                dYaArtist(id = artist.id, name = artist.name)
            },
        )
    }

    private suspend fun replaceAlbums(
        instanceId: String,
        albums: List<YaAlbum>,
    ) {
        val now = System.currentTimeMillis()
        albums.distinctBy(YaAlbum::id).forEach { album ->
            val albumId = catalogDao.ensureYandexAlbum(album.id, album.title)
            val stored = catalogDao.getAlbumMetadata(
                albumId = albumId,
                source = CATALOG_SOURCE_YANDEX,
            )
            catalogDao.upsertAlbumMetadata(
                CatalogAlbumMetadataEntity(
                    albumId = albumId,
                    source = CATALOG_SOURCE_YANDEX,
                    externalId = album.id.toString(),
                    coverUri = album.ogImageUri ?: album.coverUri ?: stored?.coverUri,
                    artistNames = album.artists
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(CATALOG_VALUE_SEPARATOR) { it.name }
                        ?: stored?.artistNames.orEmpty(),
                    genre = album.genre ?: stored?.genre,
                    releaseDate = album.releaseDate ?: stored?.releaseDate,
                    year = album.year ?: stored?.year,
                    type = album.type ?: stored?.type,
                    description = album.description ?: stored?.description,
                    likesCount = album.likesCount ?: stored?.likesCount,
                    trackCount = album.trackCount ?: stored?.trackCount,
                    refreshedAt = now,
                ),
            )
        }
        catalogDao.replaceTrackAlbumsFromYandex(
            instanceId = instanceId,
            albums = albums.map { album -> dYaAlbum(album.id, album.title) },
        )
    }

    companion object {
        const val CURRENT_RESOLVER_VERSION = 2
    }
}
