package com.yellastrodev.dwij.data.repo

import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.dao.LocalLibraryDao
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntity
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntryEntity
import com.yellastrodev.dwij.data.entities.LocalPlaylistOrigin
import com.yellastrodev.dwij.data.entities.LocalPlaylistSummary
import com.yellastrodev.dwij.data.entities.LocalTrackEntity
import com.yellastrodev.dwij.data.entities.LocalTracklist
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.source.LocalMediaSource
import com.yellastrodev.yandexmusiclib.YamLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.Executors

data class LocalSyncSummary(
    val tracks: Int,
    val playlists: Int,
    val skipped: Boolean,
)

/** Room-backed локальная медиатека; MediaStore используется только для синхронизации. */
class LocalMusicRepository(
    private val dao: LocalLibraryDao,
    private val mediaStore: LocalMediaSource,
    private val songRepository: SongRepository,
    private val database: RoomDatabase,
    private val canReadAudio: () -> Boolean,
    private val logger: YamLogger,
) {
    private val syncMutex = Mutex()
    private val _isSynchronizing = MutableStateFlow(false)
    /** Истина только во время фактического чтения MediaStore и применения снимка. */
    val isSynchronizing: StateFlow<Boolean> = _isSynchronizing

    val tracks: Flow<List<LocalTrackEntity>> = dao.observeAllTracks()
    /** Скрытые записи не теряются из индекса и доступны будущему экрану управления. */
    val hiddenTracks: Flow<List<LocalTrackEntity>> = dao.observeHiddenTracks()
    val songs: Flow<List<Song>> = songRepository.localSongs
    val playlists: Flow<List<LocalPlaylistEntity>> = dao.observePlaylists()
    val playlistSummaries: Flow<List<LocalPlaylistSummary>> = dao.observePlaylistSummaries()

    /** Ищет локальные медиафайлы в Room и возвращает собранные логические песни. */
    suspend fun searchSongs(query: String): List<Song> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return emptyList()
        return songRepository.songsForLocalTracks(dao.searchTracks(normalizedQuery))
    }

    fun playlist(playlistId: String): Flow<LocalPlaylistEntity?> =
        dao.observePlaylist(playlistId)

    fun playlistTracks(playlistId: String): Flow<List<LocalTrackEntity>> =
        dao.observePlaylistTracks(playlistId)

    fun playlistSongs(playlistId: String): Flow<List<Song>> =
        playlistTracks(playlistId).map { sourceTracks ->
            songRepository.songsForLocalTracks(sourceTracks)
        }

    fun hasAudioPermission(): Boolean = canReadAudio()

    /** Скрывает или возвращает локальный трек только в каталоге Движа, не меняя файл устройства. */
    suspend fun setTrackHidden(instanceId: String, isHidden: Boolean): DataResult<Unit> =
        setTracksHidden(listOf(instanceId), isHidden)

    /** Атомарно меняет видимость всех локальных вариантов выбранной логической песни. */
    suspend fun setTracksHidden(
        instanceIds: List<String>,
        isHidden: Boolean,
    ): DataResult<Unit> {
        val distinctIds = instanceIds.distinct()
        if (distinctIds.isEmpty()) {
            return DataResult.Failure(DataError.InvalidData("Не переданы локальные треки"))
        }
        return try {
            database.useWriterConnection { connection ->
                connection.immediateTransaction transaction@{
                    val existingIds = dao.getTracks(distinctIds)
                        .mapTo(mutableSetOf(), LocalTrackEntity::instanceId)
                    val missingIds = distinctIds.filterNot(existingIds::contains)
                    if (missingIds.isNotEmpty()) {
                        return@transaction DataResult.Failure(
                            DataError.NotFound("Локальный трек", missingIds.first()),
                        )
                    }
                    dao.setTracksHidden(distinctIds, isHidden)
                    logger.debug(
                        TAG,
                        "[setTracksHidden] tracks=${distinctIds.size}, hidden=$isHidden",
                    )
                    DataResult.Success(Unit)
                }
            }
        } catch (error: Exception) {
            logger.error(TAG, "[setTracksHidden] Не удалось изменить видимость локальных треков", error)
            DataResult.Failure(DataError.Storage(error))
        }
    }

    /** Выполняет синхронизацию последовательно на отдельном потоке с минимальным приоритетом. */
    suspend fun synchronize(force: Boolean): DataResult<LocalSyncSummary> =
        withContext(LOCAL_LIBRARY_SYNC_DISPATCHER) {
            syncMutex.withLock {
                synchronizeLocked(force)
            }
        }

    private suspend fun synchronizeLocked(force: Boolean): DataResult<LocalSyncSummary> {
        if (!hasAudioPermission()) return DataResult.Failure(DataError.Unauthorized)
        _isSynchronizing.value = true
        return try {
            val currentGeneration = mediaStore.currentGeneration()
            val savedGeneration = dao.getState(LocalLibraryDao.LAST_GENERATION_KEY)
            val storedTrackList = dao.getAllTracks()
            val changedBackingFiles = mediaStore.findChangedBackingFiles(storedTrackList)
            if (changedBackingFiles.isNotEmpty()) {
                logger.debug(
                    TAG,
                    "[synchronize] Вне MediaStore изменено файлов=${changedBackingFiles.size}",
                )
                mediaStore.rescanTracks(changedBackingFiles)
            }
            if (
                !force &&
                changedBackingFiles.isEmpty() &&
                currentGeneration == savedGeneration &&
                !currentGeneration.contains(":-1")
            ) {
                logger.debug(TAG, "[synchronize] MediaStore и файлы не изменились, сканирование пропущено")
                return DataResult.Success(LocalSyncSummary(0, 0, skipped = true))
            }
            val snapshot = mediaStore.scan()
            val storedTracks = storedTrackList.associateBy(LocalTrackEntity::instanceId)
            val scannedTracks = snapshot.tracks
                .map { scanned ->
                    scanned.copy(
                        isHidden = storedTracks[scanned.instanceId]?.isHidden ?: false,
                    )
                }
                .associateBy(LocalTrackEntity::instanceId)
            val changedTracks = scannedTracks.values.filter { track ->
                storedTracks[track.instanceId] != track
            }
            val removedTrackIds = storedTracks.keys.minus(scannedTracks.keys).toList()
            val dwijExternalUris = dao.getPlaylistsByOrigin()
                .mapNotNull(LocalPlaylistEntity::externalUri)
                .toSet()
            val importedPlaylists = snapshot.playlists.filterNot {
                it.origin == LocalPlaylistOrigin.M3U.name && it.externalUri in dwijExternalUris
            }
            val importedIds = importedPlaylists.map(LocalPlaylistEntity::playlistId).toSet()
            val tracksByUri = scannedTracks.values.associateBy(LocalTrackEntity::contentUri)
            val tracksByPath = scannedTracks.values
                .mapNotNull { track -> track.absolutePath?.let { it.normalizedPath() to track } }
                .toMap()
            database.useWriterConnection { connection ->
                connection.immediateTransaction {
                    dao.applyMediaSnapshotDiff(
                        tracksToUpsert = changedTracks,
                        trackIdsToDelete = removedTrackIds,
                        playlists = importedPlaylists,
                        entries = snapshot.entries.filter { it.playlistId in importedIds },
                        generation = snapshot.generation,
                    )
                    songRepository.registerLocalTracks(changedTracks)
                    songRepository.removeLocalTracks(removedTrackIds)
                    val resolvedDwijEntries = dao.getUnresolvedDwijEntries().mapNotNull { entry ->
                        val reference = entry.rawReference ?: return@mapNotNull null
                        val track = tracksByUri[reference] ?: tracksByPath[reference.normalizedPath()]
                        track?.let { entry.copy(localTrackId = it.instanceId) }
                    }
                    if (resolvedDwijEntries.isNotEmpty()) {
                        dao.upsertPlaylistEntries(resolvedDwijEntries)
                    }
                }
            }
            logger.debug(
                TAG,
                "[synchronize] Всего tracks=${snapshot.tracks.size}, " +
                    "изменено=${changedTracks.size}, удалено=${removedTrackIds.size}, " +
                    "playlists=${importedPlaylists.size}",
            )
            DataResult.Success(
                LocalSyncSummary(
                    tracks = snapshot.tracks.size,
                    playlists = importedPlaylists.size,
                    skipped = false,
                )
            )
        } catch (error: SecurityException) {
            logger.warning(TAG, "[synchronize] Нет доступа к MediaStore: ${error.message}")
            DataResult.Failure(DataError.Unauthorized)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.error(TAG, "[synchronize] Сканирование MediaStore завершилось ошибкой", error)
            DataResult.Failure(DataError.Storage(error))
        } finally {
            _isSynchronizing.value = false
        }
    }

    /** Создаёт или полностью обновляет плейлист Движа и его публичный M3U. */
    suspend fun saveDwijPlaylist(
        playlistId: String = "dwij:${UUID.randomUUID()}",
        name: String,
        trackIds: List<String>,
    ): DataResult<LocalPlaylistEntity> = try {
        val existing = dao.getPlaylist(playlistId)
        val trackMap = dao.getTracks(trackIds).associateBy(LocalTrackEntity::instanceId)
        val orderedTracks = trackIds.mapNotNull(trackMap::get)
        val export = mediaStore.exportM3u(name, orderedTracks, existing?.externalUri)
        val playlist = LocalPlaylistEntity(
            playlistId = playlistId,
            name = name.trim().ifBlank { "Плейлист" },
            origin = LocalPlaylistOrigin.DWIJ.name,
            externalKey = playlistId,
            externalUri = export.uri,
            dateModifiedSeconds = System.currentTimeMillis() / 1_000L,
            editable = true,
            exportedHash = export.hash,
        )
        dao.replaceDwijPlaylist(
            playlist = playlist,
            entries = trackIds.mapIndexed { position, trackId ->
                LocalPlaylistEntryEntity(
                    playlistId = playlistId,
                    position = position,
                    localTrackId = trackMap[trackId]?.instanceId,
                    rawReference = trackMap[trackId]?.absolutePath
                        ?: trackMap[trackId]?.contentUri,
                )
            },
        )
        logger.debug(TAG, "[saveDwijPlaylist] Сохранён '${playlist.name}', tracks=${trackIds.size}")
        DataResult.Success(playlist)
    } catch (error: Exception) {
        logger.error(TAG, "[saveDwijPlaylist] Не удалось сохранить плейлист", error)
        DataResult.Failure(DataError.Storage(error))
    }

    private fun String.normalizedPath(): String = replace('\\', '/').trim().lowercase()

    companion object {
        private const val TAG = "LocalMusicRepository"

        /** Один медленный worker на всё приложение с минимальным Java-приоритетом. */
        private val LOCAL_LIBRARY_SYNC_DISPATCHER = Executors.newSingleThreadExecutor { command ->
            Thread(
                command,
                "dwij-local-library-sync",
            ).apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }.asCoroutineDispatcher()

        fun tracklist(id: String, name: String): LocalTracklist = LocalTracklist(id, name)
    }
}
