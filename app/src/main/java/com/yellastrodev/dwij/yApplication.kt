package com.yellastrodev.dwij

import android.app.Application
import android.content.Context
import android.preference.PreferenceManager
import android.util.Log
import android.util.LruCache
import androidx.media3.common.util.UnstableApi
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yellastrodev.dwij.data.repo.CoverRepository
import com.yellastrodev.dwij.data.repo.PlayerRepository
import com.yellastrodev.dwij.data.repo.PlaylistRepository
import com.yellastrodev.dwij.data.repo.TrackCacheRepository
import com.yellastrodev.dwij.data.repo.TrackRepository
import com.yellastrodev.dwij.data.source.PlaylistCacheSource
import com.yellastrodev.dwij.data.source.PlaylistLocalSource
import com.yellastrodev.dwij.data.source.PlaylistRemoteSource
import com.yellastrodev.dwij.data.source.PlaybackRemoteSource
import com.yellastrodev.dwij.data.source.TrackRemoteSource
import com.yellastrodev.dwij.data.dao.dPlaylistDao
import com.yellastrodev.dwij.data.dao.dTrackDao
import com.yellastrodev.dwij.data.dao.LocalLibraryDao
import com.yellastrodev.dwij.data.dao.SongDao
import com.yellastrodev.dwij.data.dao.SongMatchDao
import com.yellastrodev.dwij.data.entities.LocalLibraryStateEntity
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntity
import com.yellastrodev.dwij.data.entities.LocalPlaylistEntryEntity
import com.yellastrodev.dwij.data.entities.LocalTrackEntity
import com.yellastrodev.dwij.data.entities.SongEntity
import com.yellastrodev.dwij.data.entities.SongMatchCandidateEntity
import com.yellastrodev.dwij.data.entities.TrackInstanceEntity
import com.yellastrodev.dwij.data.entities.dPlaylistTrack
import com.yellastrodev.dwij.data.entities.dTrackAlbumCrossRef
import com.yellastrodev.dwij.data.entities.dTrackArtistCrossRef
import com.yellastrodev.dwij.data.entities.dYaAlbum
import com.yellastrodev.dwij.data.entities.dYaArtist
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.repo.WaveRepository
import com.yellastrodev.dwij.data.repo.LocalMusicRepository
import com.yellastrodev.dwij.data.repo.SongRepository
import com.yellastrodev.dwij.data.repo.SongMatchRepository
import com.yellastrodev.dwij.data.source.LocalLibraryMonitor
import com.yellastrodev.dwij.data.source.MediaStoreLocalSource
import com.yellastrodev.dwij.data.source.WaveRemoteSource
import com.yellastrodev.dwij.data.source.SearchRemoteSource
import com.yellastrodev.dwij.data.repo.SearchRepository
import com.yellastrodev.dwij.service.PlayerService
import com.yellastrodev.dwij.service.PlaybackFeedbackTracker
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.network.YamError
import com.yellastrodev.yandexmusiclib.network.YamResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import com.yellastrodev.dwij.work.LocalLibrarySyncWorker

@UnstableApi
class yApplication: Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val yamClient: YamApiClient by lazy {
        runBlocking(Dispatchers.IO) {
            val result = initYaM(applicationContext)
            when (result) {
                is ClientResult.Error -> YamApiClient("", "", logger = YamLoggerAndroid())
                is ClientResult.Success -> result.client
            }
        }
    }



    @Database(
        entities = [
            dYaPlaylist::class,
            dPlaylistTrack::class,
            dYaTrack::class,
            dYaAlbum::class,
            dYaArtist::class,
            dTrackAlbumCrossRef::class,
            dTrackArtistCrossRef::class,
            LocalTrackEntity::class,
            LocalPlaylistEntity::class,
            LocalPlaylistEntryEntity::class,
            LocalLibraryStateEntity::class,
            SongEntity::class,
            TrackInstanceEntity::class,
            SongMatchCandidateEntity::class,
                   ],
        version = 9
    )
//    @TypeConverters(StringListConverter::class) // если у тебя есть поля List<String>
    abstract class AppDatabase : RoomDatabase() {
        abstract fun dPlaylistDao(): dPlaylistDao
        abstract fun dTrackDao(): dTrackDao
        abstract fun localLibraryDao(): LocalLibraryDao
        abstract fun songDao(): SongDao
        abstract fun songMatchDao(): SongMatchDao
    }

//    val trackLocalSource by lazy {
//        TrackLocalSource(db.dTrackDao())
//    }

    var playerServiceRef: WeakReference<PlayerService>? = null

    val trackRepository: TrackRepository by lazy {
        TrackRepository(
            TrackRemoteSource(yamClient),
            db.dTrackDao(),
            songRepository,
            applicationScope,
            )
    }

    val db by lazy {
        Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "my_database"
        )
            .addMigrations(
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
            )
            .fallbackToDestructiveMigration()
            .build()
    }

    val playlistLocalSource by lazy {
        PlaylistLocalSource(db.dPlaylistDao())
    }



    val playlistRepository: PlaylistRepository by lazy {
        val lruCache = object : LruCache<Int, dYaPlaylist>(50) {
            override fun sizeOf(key: Int, value: dYaPlaylist) = 1
        }
        PlaylistRepository(
            cache = PlaylistCacheSource(lruCache),
            remote = PlaylistRemoteSource(yamClient),
            scope = applicationScope,
            trackRepo = trackRepository,
            local = db.dPlaylistDao()

        )
    }

    val cacheManager: CacheManager by lazy{
        CacheManager(applicationContext)
    }

    val trackCacheRepo: TrackCacheRepository by lazy {
        TrackCacheRepository(
            applicationContext,
            trackRepository,
            cacheManager
        )
    }

    val playerRepo: PlayerRepository by lazy {
        PlayerRepository(
            context = applicationContext,
            scope = applicationScope,
            isTrackCached = trackCacheRepo::isCached,
        ).apply {
//            bind()

        }
    }

    val coverRepository: CoverRepository by lazy {

        CoverRepository(
            applicationContext,
            yamClient,
            cacheManager,
            applicationScope
        )
    }

    val waveRemoteSource: WaveRemoteSource by lazy {
        WaveRemoteSource(yamClient)
    }

    val searchRepository: SearchRepository by lazy {
        SearchRepository(SearchRemoteSource(yamClient))
    }

    private val mediaStoreLocalSource by lazy {
        MediaStoreLocalSource(applicationContext)
    }

    val songRepository: SongRepository by lazy {
        SongRepository(
            songDao = db.songDao(),
            matchDao = db.songMatchDao(),
            yandexTrackDao = db.dTrackDao(),
            localTrackDao = db.localLibraryDao(),
        )
    }

    val songMatchRepository: SongMatchRepository by lazy {
        SongMatchRepository(
            songDao = db.songDao(),
            matchDao = db.songMatchDao(),
        )
    }

    val localMusicRepository: LocalMusicRepository by lazy {
        LocalMusicRepository(
            context = applicationContext,
            dao = db.localLibraryDao(),
            mediaStore = mediaStoreLocalSource,
            songRepository = songRepository,
            cacheManager = cacheManager,
            database = db,
        )
    }

    private val localLibraryMonitor by lazy {
        LocalLibraryMonitor(localMusicRepository, applicationScope)
    }

    private val playbackRemoteSource: PlaybackRemoteSource by lazy {
        PlaybackRemoteSource(yamClient)
    }

    val playbackFeedbackTracker: PlaybackFeedbackTracker by lazy {
        PlaybackFeedbackTracker(
            remote = playbackRemoteSource,
            scope = applicationScope,
            isTrackCached = trackCacheRepo::isCached
        )
    }

    val waveRepository: WaveRepository by lazy {
        WaveRepository(
            waveRemoteSource,
            trackRepository,
            songRepository,
            playerRepo,
            trackCacheRepo::isCached,
            applicationScope
        )
    }


    override fun onCreate() {
        super.onCreate()
        playerRepo.waveRepository = this@yApplication.waveRepository
        LocalLibraryMonitor.observedUris(applicationContext).forEach { uri ->
            contentResolver.registerContentObserver(uri, true, localLibraryMonitor)
        }
        LocalLibrarySyncWorker.schedule(applicationContext)
        if (localMusicRepository.hasAudioPermission()) {
            LocalLibrarySyncWorker.enqueueImmediate(applicationContext)
        }
        applicationScope.launch {
            try {
                songRepository.indexExistingTracks()
                Log.d("SongRepository", "[indexExistingTracks] Индекс песен актуализирован")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(
                    "SongRepository",
                    "[indexExistingTracks] Не удалось актуализировать индекс песен",
                    error,
                )
            }
            songMatchRepository.start(applicationScope)
        }

    }

    sealed class ClientResult {
        data class Success(val client: YamApiClient) : ClientResult()
        data class Error(val reason: Reason) : ClientResult()

        enum class Reason {
            NO_TOKEN,
            NETWORK_ERROR,
            UNKNOWN
        }
    }

    suspend fun initYaM(context: Context): ClientResult {

        val sharedPref = PreferenceManager.getDefaultSharedPreferences(context)

        sharedPref.getString(YA_TOKEN, null)?.let { token ->
            if (token.isEmpty()){
                Log.i("DWIJ_TAG", "[initYaM] Нет авторизации Яндекс Музыки")
                return ClientResult.Error(ClientResult.Reason.NO_TOKEN)
            }
            var userId = sharedPref.getString(YA_ID, null)
            if (userId == null) {
                val bootstrapClient = YamApiClient(token, "", logger = YamLoggerAndroid())
                when (val statusResult = bootstrapClient.accountStatus()) {
                    is YamResult.Success -> {
                        val account = statusResult.value.account
                        val resolvedUserId = account?.uid?.toString()
                        if (resolvedUserId == null) {
                            Log.e(
                                "DWIJ_TAG",
                                "[initYaM] В account/status отсутствует uid"
                            )
                            return ClientResult.Error(ClientResult.Reason.UNKNOWN)
                        }
                        with(sharedPref.edit()) {
                            putString(YA_ID, resolvedUserId)
                            account.login?.let { putString(YA_LOGIN, it) }
                            apply()
                        }
                        userId = resolvedUserId
                    }
                    is YamResult.Failure -> {
                        Log.e(
                            "DWIJ_TAG",
                            "[initYaM] Ошибка account/status: " +
                                statusResult.error.safeName()
                        )
                        return ClientResult.Error(statusResult.error.toClientReason())
                    }
                }
            }
            val resolvedUserId = userId
                ?: return ClientResult.Error(ClientResult.Reason.UNKNOWN)
            return ClientResult.Success(YamApiClient(token, resolvedUserId, logger = YamLoggerAndroid()))
        }?: run {
            Log.i("DWIJ_TAG", "[initYaM] Нет авторизации Яндекс Музыки")
            return ClientResult.Error(ClientResult.Reason.NO_TOKEN)
        }

    }

    private fun YamError.toClientReason(): ClientResult.Reason = when (this) {
        YamError.Unauthorized -> ClientResult.Reason.NO_TOKEN
        YamError.NoInternet,
        YamError.Timeout,
        is YamError.Network -> ClientResult.Reason.NETWORK_ERROR
        is YamError.Http,
        is YamError.InvalidResponse -> ClientResult.Reason.UNKNOWN
    }

    private fun YamError.safeName(): String = when (this) {
        YamError.Unauthorized -> "Unauthorized"
        YamError.NoInternet -> "NoInternet"
        YamError.Timeout -> "Timeout"
        is YamError.Http -> "Http($statusCode)"
        is YamError.InvalidResponse -> "InvalidResponse"
        is YamError.Network -> "Network"
    }

    private companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS playlist_tracks_v4 (
                        playlistUuid TEXT NOT NULL,
                        trackId TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        PRIMARY KEY(playlistUuid, position),
                        FOREIGN KEY(playlistUuid)
                            REFERENCES playlists(playlistUuid)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO playlist_tracks_v4 (
                        playlistUuid,
                        trackId,
                        position
                    )
                    SELECT
                        current.playlistUuid,
                        current.trackId,
                        CASE
                            WHEN current.position IS NOT NULL
                                THEN current.position
                            ELSE COALESCE((
                                SELECT MAX(position) + 1
                                FROM playlist_tracks positioned
                                WHERE positioned.playlistUuid = current.playlistUuid
                                  AND positioned.position IS NOT NULL
                            ), 0) + (
                                SELECT COUNT(*) - 1
                                FROM playlist_tracks previous
                                WHERE previous.playlistUuid = current.playlistUuid
                                  AND previous.position IS NULL
                                  AND previous.rowid <= current.rowid
                            )
                        END
                    FROM playlist_tracks current
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE playlist_tracks")
                db.execSQL(
                    "ALTER TABLE playlist_tracks_v4 RENAME TO playlist_tracks"
                )
                db.execSQL(
                    "CREATE INDEX index_playlist_tracks_playlistUuid " +
                        "ON playlist_tracks(playlistUuid)"
                )
                db.execSQL(
                    "CREATE INDEX index_playlist_tracks_trackId " +
                        "ON playlist_tracks(trackId)"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_tracks (
                        instanceId TEXT NOT NULL PRIMARY KEY,
                        mediaStoreId INTEGER NOT NULL,
                        volumeName TEXT NOT NULL,
                        contentUri TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        title TEXT NOT NULL,
                        artist TEXT,
                        album TEXT,
                        albumId INTEGER,
                        durationMs INTEGER NOT NULL,
                        trackNumber INTEGER,
                        discNumber INTEGER,
                        year INTEGER,
                        mimeType TEXT,
                        sizeBytes INTEGER,
                        dateModifiedSeconds INTEGER NOT NULL,
                        relativePath TEXT,
                        absolutePath TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_local_tracks_contentUri " +
                        "ON local_tracks(contentUri)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_local_tracks_volumeName_mediaStoreId " +
                        "ON local_tracks(volumeName, mediaStoreId)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_playlists (
                        playlistId TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        origin TEXT NOT NULL,
                        externalKey TEXT NOT NULL,
                        externalUri TEXT,
                        dateModifiedSeconds INTEGER NOT NULL,
                        editable INTEGER NOT NULL,
                        exportedHash TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_local_playlists_origin_externalKey " +
                        "ON local_playlists(origin, externalKey)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_playlist_entries (
                        playlistId TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        localTrackId TEXT,
                        rawReference TEXT,
                        PRIMARY KEY(playlistId, position),
                        FOREIGN KEY(playlistId) REFERENCES local_playlists(playlistId)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(localTrackId) REFERENCES local_tracks(instanceId)
                            ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_local_playlist_entries_playlistId " +
                        "ON local_playlist_entries(playlistId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_local_playlist_entries_localTrackId " +
                        "ON local_playlist_entries(localTrackId)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS local_library_state (
                        `key` TEXT NOT NULL PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS songs (
                        songId TEXT NOT NULL PRIMARY KEY,
                        matchKey TEXT NOT NULL,
                        title TEXT NOT NULL,
                        artistNames TEXT NOT NULL,
                        albumTitle TEXT,
                        durationMs INTEGER,
                        coverUri TEXT,
                        preferredInstanceId TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_songs_matchKey " +
                        "ON songs(matchKey)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS track_instances (
                        instanceId TEXT NOT NULL PRIMARY KEY,
                        songId TEXT NOT NULL,
                        source TEXT NOT NULL,
                        sourceTrackId TEXT NOT NULL,
                        FOREIGN KEY(songId) REFERENCES songs(songId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_track_instances_songId " +
                        "ON track_instances(songId)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_track_instances_source_sourceTrackId " +
                        "ON track_instances(source, sourceTrackId)"
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tracks ADD COLUMN availabilityCheckedAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * `songs` и `track_instances` — производный индекс, поэтому безопасно пересоздаём его,
         * разлепляя все прежние автоматические совпадения. Source-таблицы не затрагиваются.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS song_match_candidates")
                db.execSQL("DROP TABLE IF EXISTS track_instances")
                db.execSQL("DROP TABLE IF EXISTS songs")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS songs (
                        songId TEXT NOT NULL PRIMARY KEY,
                        matchKey TEXT NOT NULL,
                        title TEXT NOT NULL,
                        artistNames TEXT NOT NULL,
                        albumTitle TEXT,
                        durationMs INTEGER,
                        coverUri TEXT,
                        preferredInstanceId TEXT,
                        matchResolverVersion INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_songs_matchKey ON songs(matchKey)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_songs_matchResolverVersion " +
                        "ON songs(matchResolverVersion)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS track_instances (
                        instanceId TEXT NOT NULL PRIMARY KEY,
                        songId TEXT NOT NULL,
                        source TEXT NOT NULL,
                        sourceTrackId TEXT NOT NULL,
                        FOREIGN KEY(songId) REFERENCES songs(songId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_track_instances_songId " +
                        "ON track_instances(songId)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_track_instances_source_sourceTrackId " +
                        "ON track_instances(source, sourceTrackId)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS song_match_candidates (
                        firstSongId TEXT NOT NULL,
                        secondSongId TEXT NOT NULL,
                        titleSimilarity REAL NOT NULL,
                        artistSimilarity REAL NOT NULL,
                        score REAL NOT NULL,
                        resolverVersion INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        PRIMARY KEY(firstSongId, secondSongId),
                        FOREIGN KEY(firstSongId) REFERENCES songs(songId)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(secondSongId) REFERENCES songs(songId)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_song_match_candidates_firstSongId " +
                        "ON song_match_candidates(firstSongId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_song_match_candidates_secondSongId " +
                        "ON song_match_candidates(secondSongId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_song_match_candidates_status " +
                        "ON song_match_candidates(status)"
                )
            }
        }

        /** Пользовательская видимость хранится вместе с локальным индексом и переживает sync. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE local_tracks ADD COLUMN isHidden INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
    }
}
