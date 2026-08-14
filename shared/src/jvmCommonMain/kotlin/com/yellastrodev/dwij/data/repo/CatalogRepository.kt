package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.dao.CatalogDao
import com.yellastrodev.dwij.data.entities.CATALOG_SOURCE_YANDEX
import com.yellastrodev.dwij.data.entities.CATALOG_VALUE_SEPARATOR
import com.yellastrodev.dwij.data.entities.CatalogAlbumMetadataEntity
import com.yellastrodev.dwij.data.entities.CatalogAlbumTrackEntity
import com.yellastrodev.dwij.data.entities.CatalogArtistMetadataEntity
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.dYaArtist
import com.yellastrodev.dwij.data.entities.toEntity
import com.yellastrodev.dwij.data.source.CatalogRemoteSource
import com.yellastrodev.yamusicsdk.YamLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Собранная страница артиста с явно обозначенным источником метаданных. */
data class CatalogArtistPage(
    val artistId: String,
    val name: String,
    val coverUri: String?,
    val genres: List<String>,
    val likesCount: Long?,
    val trackCount: Int?,
    val lastMonthListeners: Long?,
    val metadataSource: String?,
    val tracks: List<Song>,
)

/** Собранная страница альбома с canonical-песнями в source-порядке. */
data class CatalogAlbumPage(
    val albumId: String,
    val title: String,
    val artistNames: List<String>,
    val coverUri: String?,
    val genre: String?,
    val releaseDate: String?,
    val year: Int?,
    val type: String?,
    val description: String?,
    val likesCount: Long?,
    val metadataSource: String?,
    val tracks: List<Song>,
)

/** Собирает canonical-объекты каталога и хранит метадату отдельно по source. */
class CatalogRepository(
    private val local: CatalogDao,
    private val remote: CatalogRemoteSource,
    private val trackRepository: TrackRepository,
    private val songRepository: SongRepository,
    private val logger: YamLogger,
) {
    /**
     * Наблюдает ЯМ-артистов, подтверждённых любым source-инстансом песни.
     * Поэтому локальный трек получает навигацию к артисту без создания фальшивого ЯМ-инстанса.
     */
    fun observeYandexArtistsForSong(songId: String): Flow<List<dYaArtist>> =
        local.observeArtistSourceRefsForSong(
            songId = songId,
            source = CATALOG_SOURCE_YANDEX,
        ).map { artists ->
            artists.mapNotNull { artist ->
                artist.externalId.toIntOrNull()?.let { yandexId ->
                    dYaArtist(id = yandexId, name = artist.name)
                }
            }
        }

    /** Обновляет профиль и популярные треки артиста либо возвращает сохранённую шапку. */
    suspend fun artist(yandexId: Int): DataResult<CatalogArtistPage> {
        return when (val result = remote.artist(yandexId)) {
            is DataResult.Success -> {
                val brief = result.value
                val artist = brief.artist ?: return DataResult.Failure(
                    DataError.InvalidData("Яндекс Музыка не вернула артиста $yandexId"),
                )
                val tracks = brief.popularTracks.map { it.toEntity() }
                trackRepository.putTracks(tracks)
                val songs = songRepository.songsForYandexTracks(tracks)
                val refreshedAt = System.currentTimeMillis()
                val artistId = local.ensureYandexArtist(
                    yandexId = yandexId,
                    name = artist.name,
                )

                local.upsertArtistMetadata(
                    CatalogArtistMetadataEntity(
                        artistId = artistId,
                        source = CATALOG_SOURCE_YANDEX,
                        externalId = yandexId.toString(),
                        coverUri = artist.cover?.uri ?: artist.ogImageUri,
                        genres = artist.genres.orEmpty().joinToString(CATALOG_VALUE_SEPARATOR),
                        likesCount = artist.likesCount,
                        trackCount = artist.counts?.tracks,
                        lastMonthListeners = brief.stats?.lastMonthListeners,
                        lastMonthListenersDelta = brief.stats?.lastMonthListenersDelta,
                        refreshedAt = refreshedAt,
                    ),
                )

                DataResult.Success(
                    CatalogArtistPage(
                        artistId = artistId,
                        name = artist.name,
                        coverUri = artist.cover?.uri ?: artist.ogImageUri,
                        genres = artist.genres.orEmpty(),
                        likesCount = artist.likesCount,
                        trackCount = artist.counts?.tracks,
                        lastMonthListeners = brief.stats?.lastMonthListeners,
                        metadataSource = CATALOG_SOURCE_YANDEX,
                        tracks = songs,
                    ),
                )
            }

            is DataResult.Failure -> cachedArtist(yandexId) ?: result
        }
    }

    /** Обновляет альбом и его порядок треков либо собирает сохранённую копию. */
    suspend fun album(yandexId: Int): DataResult<CatalogAlbumPage> {
        return when (val result = remote.album(yandexId)) {
            is DataResult.Success -> {
                val album = result.value
                val flattenedTracks = album.volumes.flatten()
                val tracks = flattenedTracks.map { it.toEntity() }
                trackRepository.putTracks(tracks)
                val songs = songRepository.songsForYandexTracks(tracks)
                val refreshedAt = System.currentTimeMillis()
                val coverUri = album.ogImageUri ?: album.coverUri
                val albumId = local.ensureYandexAlbum(yandexId, album.title)

                local.upsertAlbumMetadata(
                    CatalogAlbumMetadataEntity(
                        albumId = albumId,
                        source = CATALOG_SOURCE_YANDEX,
                        externalId = yandexId.toString(),
                        coverUri = coverUri,
                        artistNames = album.artists.joinToString(CATALOG_VALUE_SEPARATOR) { it.name },
                        genre = album.genre,
                        releaseDate = album.releaseDate,
                        year = album.year,
                        type = album.type,
                        description = album.description,
                        likesCount = album.likesCount,
                        trackCount = album.trackCount ?: songs.size,
                        refreshedAt = refreshedAt,
                    ),
                )
                val positions = buildList {
                    var absolutePosition = 0
                    album.volumes.forEachIndexed { discIndex, volume ->
                        volume.forEachIndexed { trackIndex, _ ->
                            val song = songs.getOrNull(absolutePosition)
                            if (song != null) {
                                add(
                                    CatalogAlbumTrackEntity(
                                        albumId = albumId,
                                        source = CATALOG_SOURCE_YANDEX,
                                        position = absolutePosition,
                                        sourceTrackId = flattenedTracks[absolutePosition].id,
                                        discNumber = discIndex + 1,
                                        trackNumber = trackIndex + 1,
                                    ),
                                )
                            }
                            absolutePosition++
                        }
                    }
                }
                local.replaceAlbumTracks(albumId, CATALOG_SOURCE_YANDEX, positions)

                DataResult.Success(
                    CatalogAlbumPage(
                        albumId = albumId,
                        title = album.title,
                        artistNames = album.artists.map { it.name },
                        coverUri = coverUri,
                        genre = album.genre,
                        releaseDate = album.releaseDate,
                        year = album.year,
                        type = album.type,
                        description = album.description,
                        likesCount = album.likesCount,
                        metadataSource = CATALOG_SOURCE_YANDEX,
                        tracks = songs,
                    ),
                )
            }

            is DataResult.Failure -> {
                val metadata = local.getAlbumMetadataByExternalId(
                    source = CATALOG_SOURCE_YANDEX,
                    externalId = yandexId.toString(),
                )
                metadata?.albumId?.let { cachedAlbum(it) } ?: result
            }
        }
    }

    private suspend fun cachedArtist(yandexId: Int): DataResult<CatalogArtistPage>? {
        val metadata = local.getArtistMetadataByExternalId(
            source = CATALOG_SOURCE_YANDEX,
            externalId = yandexId.toString(),
        ) ?: return null
        val artistId = metadata.artistId
        val artist = local.getArtist(artistId) ?: return null
        logger.debug(TAG, "[cachedArtist] Использована сохранённая метадата artistId=$artistId")
        return DataResult.Success(
            CatalogArtistPage(
                artistId = artistId,
                name = artist.name,
                coverUri = metadata.coverUri,
                genres = metadata.genres
                    .split(CATALOG_VALUE_SEPARATOR)
                    .filter(String::isNotBlank),
                likesCount = metadata.likesCount,
                trackCount = metadata.trackCount,
                lastMonthListeners = metadata.lastMonthListeners,
                metadataSource = metadata.source,
                tracks = emptyList(),
            ),
        )
    }

    private suspend fun cachedAlbum(albumId: String): DataResult<CatalogAlbumPage>? {
        val album = local.getAlbum(albumId) ?: return null
        val metadata = local.getAlbumMetadata(albumId, CATALOG_SOURCE_YANDEX)
        val trackRefs = local.getAlbumTracks(albumId, CATALOG_SOURCE_YANDEX)
        val tracks = when (
            val result = trackRepository.getTracks(trackRefs.map { it.sourceTrackId })
        ) {
            is DataResult.Success -> result.value
            is DataResult.Failure -> emptyList()
        }
        val songs = songRepository.songsForYandexTracks(tracks)
        logger.debug(TAG, "[cachedAlbum] Использован сохранённый альбом albumId=$albumId")
        return DataResult.Success(
            CatalogAlbumPage(
                albumId = albumId,
                title = album.title,
                artistNames = metadata?.artistNames
                    ?.split(CATALOG_VALUE_SEPARATOR)
                    ?.filter(String::isNotBlank)
                    .orEmpty(),
                coverUri = metadata?.coverUri,
                genre = metadata?.genre,
                releaseDate = metadata?.releaseDate,
                year = metadata?.year,
                type = metadata?.type,
                description = metadata?.description,
                likesCount = metadata?.likesCount,
                metadataSource = metadata?.source,
                tracks = songs,
            ),
        )
    }

    private companion object {
        const val TAG = "CatalogRepository"
    }
}
