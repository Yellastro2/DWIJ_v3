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
import com.yellastrodev.dwij.activities.MainActivity
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
import com.yellastrodev.dwij.data.entities.dPlaylistTrack
import com.yellastrodev.dwij.data.entities.dTrackAlbumCrossRef
import com.yellastrodev.dwij.data.entities.dTrackArtistCrossRef
import com.yellastrodev.dwij.data.entities.dYaAlbum
import com.yellastrodev.dwij.data.entities.dYaArtist
import com.yellastrodev.dwij.data.entities.dYaPlaylist
import com.yellastrodev.dwij.data.entities.dYaTrack
import com.yellastrodev.dwij.data.repo.WaveRepository
import com.yellastrodev.dwij.data.source.WaveRemoteSource
import com.yellastrodev.dwij.service.PlayerService
import com.yellastrodev.dwij.service.PlaybackFeedbackTracker
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.network.YamError
import com.yellastrodev.yandexmusiclib.network.YamResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference

@UnstableApi
class yApplication: Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val yamClient: YamApiClient by lazy {
        runBlocking(Dispatchers.IO) {
            val result = initYaM(applicationContext)
            when (result) {
                is ClientResult.Error -> YamApiClient("", "")
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
            dTrackArtistCrossRef::class
                   ],
        version = 4
    )
//    @TypeConverters(StringListConverter::class) // если у тебя есть поля List<String>
    abstract class AppDatabase : RoomDatabase() {
        abstract fun dPlaylistDao(): dPlaylistDao
        abstract fun dTrackDao(): dTrackDao
    }

//    val trackLocalSource by lazy {
//        TrackLocalSource(db.dTrackDao())
//    }

    var playerServiceRef: WeakReference<PlayerService>? = null

    val trackRepository: TrackRepository by lazy {
        TrackRepository(
            TrackRemoteSource(yamClient),
            db.dTrackDao()
            )
    }

    val db by lazy {
        Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "my_database"
        )
            .addMigrations(MIGRATION_3_4)
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
            scope = applicationScope
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
            playerRepo,
            applicationScope
        )
    }


    override fun onCreate() {
        super.onCreate()
        playerRepo.waveRepository = this@yApplication.waveRepository

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
                withContext(Dispatchers.IO) {
                    MainActivity.LOG.info("no YandexMusic login")
                }
                return ClientResult.Error(ClientResult.Reason.NO_TOKEN)
            }
            var userId = sharedPref.getString(YA_ID, null)
            if (userId == null) {
                val bootstrapClient = YamApiClient(token, "")
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
            return ClientResult.Success(YamApiClient(token, resolvedUserId))
        }?: run {
            withContext(Dispatchers.IO) {
                MainActivity.LOG.info("no YandexMusic login")
            }
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
    }
}
