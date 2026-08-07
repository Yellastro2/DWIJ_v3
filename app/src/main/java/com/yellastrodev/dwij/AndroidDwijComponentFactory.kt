package com.yellastrodev.dwij

import android.app.Application
import androidx.media3.common.util.UnstableApi
import androidx.room.Room
import com.yellastrodev.dwij.data.db.DwijDatabase
import com.yellastrodev.dwij.data.db.buildDwijDatabase
import com.yellastrodev.dwij.data.source.MediaStoreLocalSource
import com.yellastrodev.dwij.data.source.hasAudioPermission
import com.yellastrodev.dwij.di.DwijComponent
import com.yellastrodev.dwij.playback.AndroidMediaItemMapper
import com.yellastrodev.dwij.playback.AndroidPlayerEngine
import com.yellastrodev.dwij.playback.AndroidPlayerServiceRegistry
import com.yellastrodev.dwij.storage.SharedPreferencesLocalKeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Собирает Android-реализации и передаёт их shared-компоненту.
 */
@UnstableApi
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

        return DwijComponent.create(
            applicationScope =
                applicationScope,
            logger =
                logger,
            localKeyValueStore =
                localKeyValueStore,
            db =
                database,
            trackCacheDirectory =
                File(
                    application.cacheDir,
                    DIR_TRACK_CACHE,
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
    }
}