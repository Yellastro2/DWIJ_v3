package com.yellastrodev.dwij

import android.app.Application
import android.content.Context
import android.preference.PreferenceManager
import android.util.Log
import android.util.LruCache
import androidx.media3.common.util.UnstableApi
import androidx.room.Room
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
import com.yellastrodev.dwij.data.db.DwijDatabase
import com.yellastrodev.dwij.data.db.buildDwijDatabase
import com.yellastrodev.dwij.data.entities.dYaPlaylist
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
import com.yellastrodev.dwij.utils.DwLruCache
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.network.YamError
import com.yellastrodev.yandexmusiclib.network.YamResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import com.yellastrodev.dwij.work.LocalLibrarySyncWorker
import java.io.File

@UnstableApi
class yApplication: Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val logger = YamLoggerAndroid()

    val yamClient: YamApiClient by lazy {
        runBlocking(Dispatchers.IO) {
            val result = initYaM(applicationContext)
            when (result) {
                is ClientResult.Error -> YamApiClient("", "", logger = logger)
                is ClientResult.Success -> result.client
            }
        }
    }




//
//    @Database(
//        entities = [
//            dYaPlaylist::class,
//            dPlaylistTrack::class,
//            dYaTrack::class,
//            dYaAlbum::class,
//            dYaArtist::class,
//            dTrackAlbumCrossRef::class,
//            dTrackArtistCrossRef::class,
//            LocalTrackEntity::class,
//            LocalPlaylistEntity::class,
//            LocalPlaylistEntryEntity::class,
//            LocalLibraryStateEntity::class,
//            SongEntity::class,
//            TrackInstanceEntity::class,
//            SongMatchCandidateEntity::class,
//                   ],
//        version = 9
//    )
//    @TypeConverters(StringListConverter::class) // если у тебя есть поля List<String>
//    abstract class AppDatabase : RoomDatabase() {
//        abstract fun dPlaylistDao(): dPlaylistDao
//        abstract fun dTrackDao(): dTrackDao
//        abstract fun localLibraryDao(): LocalLibraryDao
//        abstract fun songDao(): SongDao
//        abstract fun songMatchDao(): SongMatchDao
//    }

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
            logger = logger
            )
    }

    val db: DwijDatabase by lazy {
        buildDwijDatabase(Room.databaseBuilder(
            applicationContext,
            DwijDatabase::class.java,
            "my_database",
        ))
    }


    val playlistLocalSource by lazy {
        PlaylistLocalSource(db.dPlaylistDao())
    }



    val playlistRepository: PlaylistRepository by lazy {
        val lruCache = object : DwLruCache<Int, dYaPlaylist>(50) {
            override fun sizeOf(key: Int, value: dYaPlaylist) = 1
        }
        PlaylistRepository(
            cache = PlaylistCacheSource(lruCache),
            remote = PlaylistRemoteSource(yamClient, logger),
            scope = applicationScope,
            trackRepo = trackRepository,
            local = db.dPlaylistDao(),
            logger = logger
        )
    }

    val cacheManager: CacheManager by lazy{
        CacheManager(
            trackDir = File(cacheDir, DIR_TRACK_CACHE),
            coverDir = File(cacheDir, DIR_TRACK_CACHE),
            {
                PreferenceManager.getDefaultSharedPreferences(this)
                    .getLong(CACHE_SIZE, DEFAULT_CACHE_SIZE)},
            logger = logger
        )
    }

    val trackCacheRepo: TrackCacheRepository by lazy {

         val cacheDir = File(cacheDir, DIR_TRACK_CACHE).apply {
            if (!exists()) mkdirs()
        }
        TrackCacheRepository(
            cacheDir,
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
        SearchRepository(SearchRemoteSource(yamClient), logger)
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
            logger = logger
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
                val bootstrapClient = YamApiClient(token, "", logger = logger)
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
            return ClientResult.Success(YamApiClient(token, resolvedUserId, logger = logger))
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

}
