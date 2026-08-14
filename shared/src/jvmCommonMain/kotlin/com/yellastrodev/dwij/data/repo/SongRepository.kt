package com.yellastrodev.dwij.data.repo

import com.yellastrodev.dwij.data.dao.LocalLibraryDao
import com.yellastrodev.dwij.data.dao.CatalogDao
import com.yellastrodev.dwij.data.dao.SongDao
import com.yellastrodev.dwij.data.dao.SongMatchDao
import com.yellastrodev.dwij.data.dao.SongWithInstances
import com.yellastrodev.dwij.data.dao.dTrackDao
import com.yellastrodev.dwij.data.entities.Album
import com.yellastrodev.dwij.data.entities.Artist
import com.yellastrodev.dwij.data.entities.LocalTrackEntity
import com.yellastrodev.dwij.data.entities.MusicSource
import com.yellastrodev.dwij.data.entities.SONG_ARTIST_SEPARATOR
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.entities.SongEntity
import com.yellastrodev.dwij.data.entities.TrackInstance
import com.yellastrodev.dwij.data.entities.TrackInstanceEntity
import com.yellastrodev.dwij.data.entities.dYaTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Индексирует source-треки и собирает из компактных Room-связей полные [Song],
 * добавляя одним запросом признак ожидающего пользовательского решения о совпадении.
 */
class SongRepository(
    private val songDao: SongDao,
    private val matchDao: SongMatchDao,
    private val yandexTrackDao: dTrackDao,
    private val localTrackDao: LocalLibraryDao,
    private val catalogDao: CatalogDao,
) {
    private val pendingSongIds: Flow<Set<String>> = matchDao
        .observePendingSongIds()
        .map { ids -> ids.toSet() }
        .distinctUntilChanged()

    val songs: Flow<List<Song>> = combine(
        songDao.observeSongs(),
        pendingSongIds,
    ) { relations, pendingSongIds ->
        assemble(relations, pendingSongIds)
    }

    /** Локальная библиотека читается из готового индекса и никогда не пишет в Room. */
    val localSongs: Flow<List<Song>> = combine(
        songDao.observeSongsForVisibleLocalTracks(MusicSource.LOCAL.name),
        pendingSongIds,
    ) { relations, pendingSongIds ->
        assemble(relations, pendingSongIds)
    }

    /** ID логических песен, чьи Yandex-инстансы находятся в локальном liked-плейлисте. */
    val likedSongIds: Flow<Set<String>> = songDao
        .observeLikedSongIds()
        .map { ids -> ids.toSet() }
        .distinctUntilChanged()

    /** Наблюдает одну Song, включая подтверждённый локальной БД статус лайка. */
    fun song(songId: String): Flow<Song?> = combine(
        songDao.observeSong(songId),
        matchDao.observePendingCandidatesForSong(songId),
    ) { relation, pendingCandidates ->
        relation?.let {
            assemble(
                relations = listOf(it),
                pendingSongIds = if (pendingCandidates.isEmpty()) emptySet() else setOf(songId),
            ).firstOrNull()
        }
    }.distinctUntilChanged()

    /** Индексирует уже сохранённые записи после запуска или миграции приложения. */
    suspend fun indexExistingTracks() {
        val yandexIds = songDao.getUnindexedYandexTrackIds(MusicSource.YANDEX.name)
        if (yandexIds.isNotEmpty()) {
            registerYandexTracks(yandexTrackDao.getTracks(yandexIds))
        }
        val localIds = songDao.getUnindexedLocalTrackIds(MusicSource.LOCAL.name)
        if (localIds.isNotEmpty()) {
            registerLocalTracks(localTrackDao.getTracks(localIds))
        }
    }

    suspend fun registerYandexTracks(tracks: List<dYaTrack>) {
        tracks.distinctBy(dYaTrack::id).forEach { track ->
            val song = track.toSongEntity()
            val instance = TrackInstanceEntity(
                instanceId = yandexInstanceId(track.id),
                songId = song.songId,
                source = MusicSource.YANDEX.name,
                sourceTrackId = track.id,
            )
            songDao.link(
                song = song,
                instance = instance,
            )
            catalogDao.replaceTrackArtistsFromYandex(
                instanceId = instance.instanceId,
                artists = track.artists,
            )
            catalogDao.replaceTrackAlbumsFromYandex(
                instanceId = instance.instanceId,
                albums = track.albums,
            )
        }
        songDao.deleteOrphanSongs()
    }

    /**
     * Одним чтением проверяет производный индекс и восстанавливает только отсутствующие связи.
     * Это страхует незавершённую запись или фоновую стартовую индексацию, не обновляя уже
     * зарегистрированные треки при каждом открытии списка.
     */
    suspend fun ensureYandexTracksIndexed(tracks: List<dYaTrack>) {
        val distinctTracks = tracks.distinctBy(dYaTrack::id)
        if (distinctTracks.isEmpty()) return

        val indexedSourceIds = songDao.getInstances(
            source = MusicSource.YANDEX.name,
            sourceTrackIds = distinctTracks.map(dYaTrack::id),
        ).mapTo(mutableSetOf(), TrackInstanceEntity::sourceTrackId)
        val missingTracks = distinctTracks.filterNot { track ->
            track.id in indexedSourceIds
        }
        if (missingTracks.isNotEmpty()) {
            registerYandexTracks(missingTracks)
        }
    }

    suspend fun registerLocalTracks(tracks: List<LocalTrackEntity>) {
        tracks.distinctBy(LocalTrackEntity::instanceId).forEach { track ->
            val song = track.toSongEntity()
            songDao.link(
                song = song,
                instance = TrackInstanceEntity(
                    instanceId = track.instanceId,
                    songId = song.songId,
                    source = MusicSource.LOCAL.name,
                    sourceTrackId = track.instanceId,
                ),
            )
        }
    }

    suspend fun removeLocalTracks(trackIds: List<String>) {
        if (trackIds.isNotEmpty()) {
            songDao.deleteInstances(MusicSource.LOCAL.name, trackIds)
            songDao.deleteOrphanSongs()
        }
    }

    /** Возвращает песни в том же порядке и с теми же повторами, что и Яндекс-треки. */
    suspend fun songsForYandexTracks(tracks: List<dYaTrack>): List<Song> {
        if (tracks.isEmpty()) return emptyList()
        return songsForSourceIds(
            source = MusicSource.YANDEX,
            sourceIds = tracks.map(dYaTrack::id),
        )
    }

    /** Возвращает песни в том же порядке и с теми же повторами, что и локальные файлы. */
    suspend fun songsForLocalTracks(tracks: List<LocalTrackEntity>): List<Song> {
        if (tracks.isEmpty()) return emptyList()
        return songsForSourceIds(
            source = MusicSource.LOCAL,
            sourceIds = tracks.map(LocalTrackEntity::instanceId),
        )
    }

    /** Собирает актуальные Song по ID для диалога возможных source-вариантов. */
    suspend fun songsByIds(songIds: List<String>): List<Song> {
        if (songIds.isEmpty()) return emptyList()
        val relations = songDao.getSongs(songIds.distinct())
        return assemble(
            relations = relations,
            pendingSongIds = matchDao.getPendingSongIds().toSet(),
        )
    }

    /** Сохраняет явный выбор экземпляра; null возвращает автоматический выбор источника. */
    suspend fun setPreferredInstance(songId: String, instanceId: String?) {
        if (instanceId != null) {
            val instance = requireNotNull(songDao.getInstance(instanceId)) {
                "Экземпляр $instanceId не найден"
            }
            require(instance.songId == songId) {
                "Экземпляр $instanceId не принадлежит песне $songId"
            }
        }
        songDao.updatePreferredInstance(songId, instanceId)
    }

    /**
     * Объединяет все логические песни, которым принадлежат [instances], в песню первого экземпляра.
     * Возвращает ID сохранённой песни; операция целиком выполняется в одной Room-транзакции.
     */
    suspend fun mergeInstances(instances: List<TrackInstance>): String =
        songDao.mergeInstances(instances.map(TrackInstance::id))

    private suspend fun songsForSourceIds(
        source: MusicSource,
        sourceIds: List<String>,
    ): List<Song> {
        val links = songDao.getInstances(source.name, sourceIds)
            .associateBy(TrackInstanceEntity::sourceTrackId)
        val relations = songDao.getSongs(links.values.map(TrackInstanceEntity::songId).distinct())
        val pendingSongIds = matchDao.getPendingSongIds().toSet()
        val songsById = assemble(relations, pendingSongIds).associateBy(Song::id)
        return sourceIds.mapNotNull { sourceId ->
            val link = links[sourceId] ?: return@mapNotNull null
            val song = songsById[link.songId] ?: return@mapNotNull null
            // Сохраняем контекст исходного списка: его экземпляр идёт первым среди
            // инстансов того же источника. Это важно для source-specific UI и feedback,
            // но не отменяет явный preferredInstanceId при выборе воспроизведения.
            song.copy(
                instances = song.instances.sortedByDescending { instance ->
                    instance.id == link.instanceId
                },
            )
        }
    }

    private suspend fun assemble(
        relations: List<SongWithInstances>,
        pendingSongIds: Set<String>,
    ): List<Song> {
        if (relations.isEmpty()) return emptyList()
        val links = relations.flatMap(SongWithInstances::instances)
        val yandexIds = links.filter { it.source == MusicSource.YANDEX.name }
            .map(TrackInstanceEntity::sourceTrackId)
            .distinct()
        val localIds = links.filter { it.source == MusicSource.LOCAL.name }
            .map(TrackInstanceEntity::sourceTrackId)
            .distinct()
        val yandexTracks = if (yandexIds.isEmpty()) {
            emptyMap()
        } else {
            yandexTrackDao.getTracks(yandexIds).associateBy(dYaTrack::id)
        }
        val localTracks = if (localIds.isEmpty()) {
            emptyMap()
        } else {
            localTrackDao.getTracks(localIds).associateBy(LocalTrackEntity::instanceId)
        }

        return relations.map { relation ->
            val instances = relation.instances.mapNotNull { link ->
                when (link.source) {
                    MusicSource.YANDEX.name -> yandexTracks[link.sourceTrackId]?.let { track ->
                        TrackInstance.Yandex(link.instanceId, track)
                    }
                    MusicSource.LOCAL.name -> localTracks[link.sourceTrackId]?.let { track ->
                        TrackInstance.Local(link.instanceId, track)
                    }
                    else -> null
                }
            }
            relation.song.toDomain(
                instances = instances,
                hasPendingMatchCandidate = relation.song.songId in pendingSongIds,
                isLiked = relation.isLiked,
            )
        }
    }

    private fun dYaTrack.toSongEntity(): SongEntity = newSongEntity(
        title = title,
        artistNames = artists.map { it.name },
        albumTitle = albums.firstOrNull()?.title,
        durationMs = durationMs?.toLong(),
        coverUri = getCoverUriAny(),
    )

    private fun LocalTrackEntity.toSongEntity(): SongEntity = newSongEntity(
        title = title,
        artistNames = artist?.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty(),
        albumTitle = album,
        durationMs = durationMs,
        coverUri = albumId?.let { "content://media/external/audio/albumart/$it" },
    )

    private fun newSongEntity(
        title: String,
        artistNames: List<String>,
        albumTitle: String?,
        durationMs: Long?,
        coverUri: String?,
    ): SongEntity = SongEntity(
        songId = UUID.randomUUID().toString(),
        matchKey = matchKey(title, artistNames, albumTitle),
        title = title,
        artistNames = artistNames.joinToString(SONG_ARTIST_SEPARATOR),
        albumTitle = albumTitle,
        durationMs = durationMs,
        coverUri = coverUri,
        preferredInstanceId = null,
    )

    private fun SongEntity.toDomain(
        instances: List<TrackInstance>,
        hasPendingMatchCandidate: Boolean,
        isLiked: Boolean,
    ): Song = Song(
        id = songId,
        title = title,
        artists = instances
            .filterIsInstance<TrackInstance.Yandex>()
            .flatMap { instance -> instance.track.artists.map { it.name } }
            .ifEmpty {
                artistNames.split(SONG_ARTIST_SEPARATOR).filter(String::isNotBlank)
            }
            .distinctBy { name -> normalize(name) }
            .map { name -> Artist(id = logicalId("artist", name), name = name) },
        albums = buildList {
            instances.filterIsInstance<TrackInstance.Yandex>().forEach { instance ->
                instance.track.albums.forEach { album ->
                    add(
                        Album(
                            id = logicalId("album", album.title),
                            title = album.title,
                            coverUri = coverUri,
                        )
                    )
                }
            }
            instances.filterIsInstance<TrackInstance.Local>().forEach { instance ->
                instance.track.album?.takeIf { it.isNotBlank() }?.let { title ->
                    add(
                        Album(
                            id = logicalId("album", title),
                            title = title,
                            year = instance.track.year,
                            coverUri = instance.track.albumId?.let {
                                "content://media/external/audio/albumart/$it"
                            },
                        )
                    )
                }
            }
            if (isEmpty()) {
                albumTitle?.let { title ->
                    add(Album(id = logicalId("album", title), title = title, coverUri = coverUri))
                }
            }
        }.distinctBy { album -> normalize(album.title) },
        durationMs = durationMs,
        coverUri = coverUri,
        instances = instances,
        preferredInstanceId = preferredInstanceId,
        hasPendingMatchCandidate = hasPendingMatchCandidate,
        isLiked = isLiked,
    )

    private fun matchKey(
        title: String,
        artistNames: List<String>,
        albumTitle: String?,
    ): String = listOf(
        normalize(title),
        normalize(artistNames.joinToString(" ")),
        normalize(albumTitle.orEmpty()),
    ).joinToString("|")

    private fun normalize(value: String): String = value
        .lowercase()
        .replace('ё', 'е')
        .replace(NON_ALPHANUMERIC, " ")
        .trim()
        .replace(MULTIPLE_SPACES, " ")

    private fun yandexInstanceId(trackId: String): String = "yandex:$trackId"

    private fun logicalId(type: String, value: String): String = "$type:${normalize(value)}"

    private companion object {
        val NON_ALPHANUMERIC = Regex("[^\\p{L}\\p{N}]+")
        val MULTIPLE_SPACES = Regex("\\s+")
    }
}
