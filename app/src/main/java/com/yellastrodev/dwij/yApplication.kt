package com.yellastrodev.dwij

import android.app.Application
import android.content.Context
import android.preference.PreferenceManager
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.room.Room
import com.yellastrodev.dwij.data.db.DwijDatabase
import com.yellastrodev.dwij.data.db.buildDwijDatabase
import com.yellastrodev.dwij.data.source.LocalLibraryMonitor
import com.yellastrodev.dwij.data.source.MediaStoreLocalSource
import com.yellastrodev.dwij.data.source.hasAudioPermission
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.playback.AndroidMediaItemMapper
import com.yellastrodev.dwij.playback.AndroidPlaybackFeedbackAdapter
import com.yellastrodev.dwij.playback.AndroidPlaybackSettings
import com.yellastrodev.dwij.playback.AndroidPlayerEngine
import com.yellastrodev.dwij.playback.PlaybackSettings
import com.yellastrodev.dwij.playback.PlayerEngine
import com.yellastrodev.dwij.service.PlayerService
import com.yellastrodev.dwij.work.LocalLibrarySyncWorker
import com.yellastrodev.yandexmusiclib.YamApiClient
import com.yellastrodev.yandexmusiclib.network.YamError
import com.yellastrodev.yandexmusiclib.network.YamResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File
import java.lang.ref.WeakReference

@UnstableApi
class yApplication : Application() {

    private val applicationScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO,
        )

    val logger = YamLoggerAndroid()

    val yamClient: YamApiClient by lazy {
        runBlocking(Dispatchers.IO) {
            when (
                val result = initYaM(
                    applicationContext,
                )
            ) {
                is ClientResult.Error -> {
                    YamApiClient(
                        accessToken = "",
                        userId = "",
                        logger = logger,
                    )
                }

                is ClientResult.Success -> {
                    result.client
                }
            }
        }
    }

    var playerServiceRef: WeakReference<PlayerService>? = null

    val db: DwijDatabase by lazy {
        buildDwijDatabase(
            Room.databaseBuilder(
                applicationContext,
                DwijDatabase::class.java,
                DATABASE_NAME,
            ),
        )
    }

    val playbackSettings: PlaybackSettings by lazy {
        AndroidPlaybackSettings(
            context = applicationContext,
        )
    }

    val mediaItemMapper: AndroidMediaItemMapper by lazy {
        AndroidMediaItemMapper()
    }

    val musicSourceSettings: MusicSourceSettings by lazy {
        AndroidMusicSourceSettings(
            context = applicationContext,
        )
    }

    val playerEngine: PlayerEngine by lazy {
        AndroidPlayerEngine(
            context = applicationContext,
            scope = applicationScope,
            mediaItemMapper = mediaItemMapper,
        )
    }

    private val mediaStoreLocalSource by lazy {
        MediaStoreLocalSource(
            context = applicationContext,
        )
    }

    /**
     * Общий граф зависимостей приложения.
     *
     * yApplication создаёт только Android-зависимости
     * и передаёт их в shared-компонент.
     */
    val component: DwijComponent by lazy {
        DwijComponent(
            applicationScope = applicationScope,
            logger = logger,
            yamClient = yamClient,
            db = db,
            trackCacheDirectory = File(
                cacheDir,
                DIR_TRACK_CACHE,
            ),
            coverCacheDirectory = File(
                cacheDir,
                DIR_COVER_CACHE,
            ),
            maxCacheSizeBytes = {
                PreferenceManager
                    .getDefaultSharedPreferences(
                        applicationContext,
                    )
                    .getLong(
                        CACHE_SIZE,
                        DEFAULT_CACHE_SIZE,
                    )
            },
            playbackSettings = playbackSettings,
            playerEngine = playerEngine,
            musicSourceSettings = musicSourceSettings,
            localMediaSource = mediaStoreLocalSource,
            canReadAudio = {
                hasAudioPermission(
                    applicationContext,
                )
            },
        )
    }

    /*
     * Временные совместимые getters.
     *
     * Пока routes, PlayerService и Worker обращаются
     * непосредственно к yApplication, их менять не нужно.
     * При дальнейшем переносе они начнут получать component
     * или узкие dependency-объекты напрямую.
     */
    val trackRepository
        get() = component.trackRepository

    val playlistRepository
        get() = component.playlistRepository

    val cacheManager
        get() = component.cacheManager

    val trackCacheRepo
        get() = component.trackCacheRepo

    val musicSourceSelectionStore
        get() = component.musicSourceSelectionStore

    val playerRepo
        get() = component.playerRepo

    val coverFileCache
        get() = component.coverFileCache

    val coverRepository
        get() = component.coverRepository

    val trackCoverLoader
        get() = component.trackCoverLoader

    val waveRemoteSource
        get() = component.waveRemoteSource

    val searchRepository
        get() = component.searchRepository

    val songRepository
        get() = component.songRepository

    val songMatchRepository
        get() = component.songMatchRepository

    val localMusicRepository
        get() = component.localMusicRepository

    val playbackFeedbackTracker
        get() = component.playbackFeedbackTracker

    val waveRepository
        get() = component.waveRepository

    val playbackFeedbackAdapter: AndroidPlaybackFeedbackAdapter by lazy {
        AndroidPlaybackFeedbackAdapter(
            tracker = component.playbackFeedbackTracker,
        )
    }

    private val localLibraryMonitor by lazy {
        LocalLibraryMonitor(
            repository = component.localMusicRepository,
            scope = applicationScope,
        )
    }

    override fun onCreate() {
        super.onCreate()

        LocalLibraryMonitor
            .observedUris(
                applicationContext,
            )
            .forEach { uri ->
                contentResolver.registerContentObserver(
                    uri,
                    true,
                    localLibraryMonitor,
                )
            }

        LocalLibrarySyncWorker.schedule(
            applicationContext,
        )

        if (
            component
                .localMusicRepository
                .hasAudioPermission()
        ) {
            LocalLibrarySyncWorker.enqueueImmediate(
                applicationContext,
            )
        }

        component.start()
    }

    sealed class ClientResult {
        data class Success(
            val client: YamApiClient,
        ) : ClientResult()

        data class Error(
            val reason: Reason,
        ) : ClientResult()

        enum class Reason {
            NO_TOKEN,
            NETWORK_ERROR,
            UNKNOWN,
        }
    }

    suspend fun initYaM(
        context: Context,
    ): ClientResult {
        val sharedPreferences =
            PreferenceManager.getDefaultSharedPreferences(
                context,
            )

        sharedPreferences
            .getString(
                YA_TOKEN,
                null,
            )
            ?.let { token ->
                if (token.isEmpty()) {
                    Log.i(
                        TAG,
                        "[initYaM] Нет авторизации Яндекс Музыки",
                    )

                    return ClientResult.Error(
                        ClientResult.Reason.NO_TOKEN,
                    )
                }

                var userId = sharedPreferences.getString(
                    YA_ID,
                    null,
                )

                if (userId == null) {
                    val bootstrapClient = YamApiClient(
                        accessToken = token,
                        userId = "",
                        logger = logger,
                    )

                    when (
                        val statusResult =
                            bootstrapClient.accountStatus()
                    ) {
                        is YamResult.Success -> {
                            val account =
                                statusResult.value.account

                            val resolvedUserId =
                                account
                                    ?.uid
                                    ?.toString()

                            if (resolvedUserId == null) {
                                Log.e(
                                    TAG,
                                    "[initYaM] В account/status отсутствует uid",
                                )

                                return ClientResult.Error(
                                    ClientResult.Reason.UNKNOWN,
                                )
                            }

                            with(
                                sharedPreferences.edit(),
                            ) {
                                putString(
                                    YA_ID,
                                    resolvedUserId,
                                )

                                account.login?.let { login ->
                                    putString(
                                        YA_LOGIN,
                                        login,
                                    )
                                }

                                apply()
                            }

                            userId = resolvedUserId
                        }

                        is YamResult.Failure -> {
                            Log.e(
                                TAG,
                                "[initYaM] Ошибка account/status: " +
                                        statusResult.error.safeName(),
                            )

                            return ClientResult.Error(
                                statusResult
                                    .error
                                    .toClientReason(),
                            )
                        }
                    }
                }

                val resolvedUserId =
                    userId
                        ?: return ClientResult.Error(
                            ClientResult.Reason.UNKNOWN,
                        )

                return ClientResult.Success(
                    YamApiClient(
                        accessToken = token,
                        userId = resolvedUserId,
                        logger = logger,
                    ),
                )
            }

        Log.i(
            TAG,
            "[initYaM] Нет авторизации Яндекс Музыки",
        )

        return ClientResult.Error(
            ClientResult.Reason.NO_TOKEN,
        )
    }

    private fun YamError.toClientReason(): ClientResult.Reason =
        when (this) {
            YamError.Unauthorized ->
                ClientResult.Reason.NO_TOKEN

            YamError.NoInternet,
            YamError.Timeout,
            is YamError.Network ->
                ClientResult.Reason.NETWORK_ERROR

            is YamError.Http,
            is YamError.InvalidResponse ->
                ClientResult.Reason.UNKNOWN
        }

    private fun YamError.safeName(): String =
        when (this) {
            YamError.Unauthorized ->
                "Unauthorized"

            YamError.NoInternet ->
                "NoInternet"

            YamError.Timeout ->
                "Timeout"

            is YamError.Http ->
                "Http($statusCode)"

            is YamError.InvalidResponse ->
                "InvalidResponse"

            is YamError.Network ->
                "Network"
        }

    private companion object {
        const val TAG = "DWIJ_TAG"
        const val DATABASE_NAME = "my_database"
    }
}