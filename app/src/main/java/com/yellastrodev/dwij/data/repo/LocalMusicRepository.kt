package com.yellastrodev.dwij.data.repo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.dao.LocalLibraryDao
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntity
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntryEntity
import com.yellastrodev.dwij.data.entities.LocalPlaylistOrigin
import com.yellastrodev.dwij.data.entities.LocalTrackEntity
import com.yellastrodev.dwij.data.entities.LocalTracklist
import com.yellastrodev.dwij.data.entities.Song
import com.yellastrodev.dwij.data.source.MediaStoreLocalSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class LocalSyncSummary(
    val tracks: Int,
    val playlists: Int,
    val skipped: Boolean,
)

/** Room-backed локальная медиатека; MediaStore используется только для синхронизации. */
class LocalMusicRepository(
    private val context: Context,
    private val dao: LocalLibraryDao,
    private val mediaStore: MediaStoreLocalSource,
    private val songRepository: SongRepository,
) {
    private val syncMutex = Mutex()

    val tracks: Flow<List<LocalTrackEntity>> = dao.observeAllTracks()
    val songs: Flow<List<Song>> = tracks.map { sourceTracks ->
        songRepository.songsForLocalTracks(sourceTracks)
    }
    val playlists: Flow<List<LocalPlaylistEntity>> = dao.observePlaylists()

    fun playlist(playlistId: String): Flow<LocalPlaylistEntity?> =
        dao.observePlaylist(playlistId)

    fun playlistTracks(playlistId: String): Flow<List<LocalTrackEntity>> =
        dao.observePlaylistTracks(playlistId)

    fun playlistSongs(playlistId: String): Flow<List<Song>> =
        playlistTracks(playlistId).map { sourceTracks ->
            songRepository.songsForLocalTracks(sourceTracks)
        }

    fun hasAudioPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        requiredAudioPermission(),
    ) == PackageManager.PERMISSION_GRANTED

    suspend fun synchronize(force: Boolean): DataResult<LocalSyncSummary> = syncMutex.withLock {
        if (!hasAudioPermission()) return DataResult.Failure(DataError.Unauthorized)
        try {
            val currentGeneration = mediaStore.currentGeneration()
            val savedGeneration = dao.getState(LocalLibraryDao.LAST_GENERATION_KEY)
            if (!force && currentGeneration == savedGeneration && !currentGeneration.contains(":-1")) {
                Log.d(TAG, "[synchronize] MediaStore не изменился, сканирование пропущено")
                return DataResult.Success(LocalSyncSummary(0, 0, skipped = true))
            }
            val snapshot = mediaStore.scan()
            val dwijExternalUris = dao.getPlaylistsByOrigin()
                .mapNotNull(LocalPlaylistEntity::externalUri)
                .toSet()
            val importedPlaylists = snapshot.playlists.filterNot {
                it.origin == LocalPlaylistOrigin.M3U.name && it.externalUri in dwijExternalUris
            }
            val importedIds = importedPlaylists.map(LocalPlaylistEntity::playlistId).toSet()
            dao.replaceMediaSnapshot(
                tracks = snapshot.tracks,
                playlists = importedPlaylists,
                entries = snapshot.entries.filter { it.playlistId in importedIds },
                generation = snapshot.generation,
            )
            songRepository.registerLocalTracks(snapshot.tracks, removeMissing = true)
            val tracksByUri = snapshot.tracks.associateBy(LocalTrackEntity::contentUri)
            val tracksByPath = snapshot.tracks
                .mapNotNull { track -> track.absolutePath?.let { it.normalizedPath() to track } }
                .toMap()
            val resolvedDwijEntries = dao.getUnresolvedDwijEntries().mapNotNull { entry ->
                val reference = entry.rawReference ?: return@mapNotNull null
                val track = tracksByUri[reference] ?: tracksByPath[reference.normalizedPath()]
                track?.let { entry.copy(localTrackId = it.instanceId) }
            }
            if (resolvedDwijEntries.isNotEmpty()) {
                dao.upsertPlaylistEntries(resolvedDwijEntries)
            }
            Log.d(
                TAG,
                "[synchronize] Обновлено: tracks=${snapshot.tracks.size}, " +
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
            Log.w(TAG, "[synchronize] Нет доступа к MediaStore", error)
            DataResult.Failure(DataError.Unauthorized)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "[synchronize] Сканирование MediaStore завершилось ошибкой", error)
            DataResult.Failure(DataError.Storage(error))
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
        Log.d(TAG, "[saveDwijPlaylist] Сохранён '${playlist.name}', tracks=${trackIds.size}")
        DataResult.Success(playlist)
    } catch (error: Exception) {
        Log.e(TAG, "[saveDwijPlaylist] Не удалось сохранить плейлист", error)
        DataResult.Failure(DataError.Storage(error))
    }

    fun cover(track: LocalTrackEntity): Flow<Bitmap> = flow {
        val bitmap = track.albumId?.let { albumId ->
            runCatching {
                context.contentResolver.openInputStream(
                    Uri.parse("content://media/external/audio/albumart/$albumId")
                )?.use(BitmapFactory::decodeStream)
            }.getOrNull()
        } ?: embeddedCover(track)
            ?: BitmapFactory.decodeResource(context.resources, R.drawable.logo2)
        emit(bitmap)
    }

    private fun embeddedCover(track: LocalTrackEntity): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(track.contentUri))
            retriever.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } catch (error: Exception) {
            Log.d(TAG, "[embeddedCover] Встроенная обложка недоступна: ${track.instanceId}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun String.normalizedPath(): String = replace('\\', '/').trim().lowercase()

    companion object {
        private const val TAG = "LocalMusicRepository"

        fun requiredAudioPermission(): String = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT <= 28) {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            )
        } else {
            arrayOf(requiredAudioPermission())
        }

        fun tracklist(id: String, name: String): LocalTracklist = LocalTracklist(id, name)
    }
}
