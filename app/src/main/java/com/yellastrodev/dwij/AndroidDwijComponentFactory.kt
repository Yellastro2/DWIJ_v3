package com.yellastrodev.dwij

import android.app.Application

import androidx.room.Room
import com.yellastrodev.dwij.data.db.DwijDatabase
import com.yellastrodev.dwij.data.db.buildDwijDatabase
import com.yellastrodev.dwij.data.source.MediaStoreLocalSource
import com.yellastrodev.dwij.data.source.hasAudioPermission
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.playback.AndroidMediaItemMapper
import com.yellastrodev.dwij.playback.AndroidPlayerEngine
import com.yellastrodev.dwij.playback.AndroidPlayerServiceRegistry
import com.yellastrodev.dwij.storage.AndroidKeystoreSessionPayloadStore
import com.yellastrodev.dwij.storage.MigratingYandexSessionStore
import com.yellastrodev.dwij.storage.ProtectedYandexSessionStore
import com.yellastrodev.dwij.storage.SharedPreferencesLocalKeyValueStore
import com.yellastrodev.dwij.storage.StoredYandexSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Собирает Android-реализации и передаёт их shared-компоненту.
 */
class AndroidDwijComponentFactory(
    private val application: Application,
    private val playerServiceRegistry:
    AndroidPlayerServiceRegistry,
) {

    fun create(): DwijComponent {
        val context =
            application.applicationContext

        val applicationScope =
            CoroutineScope(
                SupervisorJob() +
                        Dispatchers.IO,
            )

        val logger =
            YamLoggerAndroid()

        val database =
            buildDwijDatabase(
                Room.databaseBuilder(
                    context,
                    DwijDatabase::class.java,
                    DATABASE_NAME,
                ),
            )

        val mediaItemMapper =
            AndroidMediaItemMapper()

        val localKeyValueStore =
            SharedPreferencesLocalKeyValueStore(
                context,
            )

        val yandexSessionStore =
            MigratingYandexSessionStore(
                primary =
                    ProtectedYandexSessionStore(
                        AndroidKeystoreSessionPayloadStore(
                            context =
                                context,
                            logger =
                                logger,
                        ),
                    ),
                legacy =
                    StoredYandexSessionStore(
                        localKeyValueStore,
                    ),
                migrationState =
                    localKeyValueStore,
                logger =
                    logger,
            )

        return DwijComponent.create(
            applicationScope =
                applicationScope,
            logger =
                logger,
            localKeyValueStore =
                localKeyValueStore,
            yandexSessionStore =
                yandexSessionStore,
            db =
                database,
            trackCacheDirectory =
                File(
                    application.cacheDir,
                    DIR_TRACK_CACHE,
                ),
            localYandexTrackDirectory =
                File(
                    application.noBackupFilesDir,
                    DIR_LOCAL_YANDEX_TRACKS,
                ),
            coverCacheDirectory =
                File(
                    application.cacheDir,
                    DIR_COVER_CACHE,
                ),
            playerEngine =
                AndroidPlayerEngine(
                    context =
                        context,
                    scope =
                        applicationScope,
                    mediaItemMapper =
                        mediaItemMapper,
                    serviceRegistry =
                        playerServiceRegistry,
                ),
            localMediaSource =
                MediaStoreLocalSource(
                    context,
                ),
            canReadAudio = {
                hasAudioPermission(
                    context,
                )
            },
            platformLifecycle =
                AndroidDwijPlatformLifecycle(
                    context,
                ),
        )
    }

    private companion object {
        const val DATABASE_NAME =
            "my_database"
        const val DIR_LOCAL_YANDEX_TRACKS =
            "yandex-tracks"
    }
}
