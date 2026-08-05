package com.yellastrodev.dwij.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerNotificationManager
import com.yellastrodev.dwij.TRACK_ID
import com.yellastrodev.dwij.activities.MainActivity
import com.yellastrodev.dwij.playback.TrackCoverLoader
import com.yellastrodev.dwij.yApplication
import com.yellastrodev.yandexmusiclib.entities.CoverSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class yPushMediaAdapterobject(
    private val playerService: PlayerService,
) : PlayerNotificationManager.MediaDescriptionAdapter {

    private val trackCoverLoader: TrackCoverLoader by lazy {
        val application =
            playerService.application as yApplication

        application.trackCoverLoader
    }

    private var coverJob: Job? = null

    override fun getCurrentContentTitle(
        player: Player,
    ): CharSequence {
        val title =
            player.currentMediaItem
                ?.mediaMetadata
                ?.title
                ?: UNKNOWN_TITLE

        Log.d(
            TAG,
            "[getCurrentContentTitle] title=$title",
        )

        return title
    }

    override fun getCurrentContentText(
        player: Player,
    ): CharSequence? {
        val artist =
            player.currentMediaItem
                ?.mediaMetadata
                ?.artist

        Log.d(
            TAG,
            "[getCurrentContentText] artist=$artist",
        )

        return artist
    }

    override fun createCurrentContentIntent(
        player: Player,
    ): PendingIntent {
        val intent = Intent(
            playerService,
            MainActivity::class.java,
        ).apply {
            flags =
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        return PendingIntent.getActivity(
            playerService,
            CONTENT_INTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or
                    PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun getCurrentLargeIcon(
        player: Player,
        callback: PlayerNotificationManager.BitmapCallback,
    ): Bitmap? {
        coverJob?.cancel()

        val mediaItem =
            player.currentMediaItem
                ?: return null

        val trackId =
            mediaItem.mediaMetadata
                .extras
                ?.getString(TRACK_ID)
                ?.takeIf(String::isNotBlank)
                ?: mediaItem.mediaId
                    .takeIf(String::isNotBlank)
                ?: return null

        Log.d(
            TAG,
            "[getCurrentLargeIcon] trackId=$trackId",
        )

        coverJob = playerService.serviceScope.launch(
            Dispatchers.IO,
        ) {
            val coverBytes = trackCoverLoader.load(
                trackId = trackId,
                size = CoverSize.`100x100`,
            ) ?: run {
                Log.d(
                    TAG,
                    "[getCurrentLargeIcon] " +
                            "Обложка отсутствует, trackId=$trackId",
                )
                return@launch
            }

            val bitmap = BitmapFactory.decodeByteArray(
                coverBytes,
                0,
                coverBytes.size,
            ) ?: run {
                Log.w(
                    TAG,
                    "[getCurrentLargeIcon] " +
                            "Не удалось декодировать обложку, " +
                            "trackId=$trackId",
                )
                return@launch
            }

            /*
             * Пока загружалась обложка, пользователь мог
             * переключить трек.
             */
            val actualTrackId =
                player.currentMediaItem
                    ?.mediaMetadata
                    ?.extras
                    ?.getString(TRACK_ID)
                    ?.takeIf(String::isNotBlank)
                    ?: player.currentMediaItem
                        ?.mediaId
                        ?.takeIf(String::isNotBlank)

            if (actualTrackId != trackId) {
                Log.d(
                    TAG,
                    "[getCurrentLargeIcon] " +
                            "Трек уже изменился: " +
                            "requested=$trackId, actual=$actualTrackId",
                )
                return@launch
            }

            withContext(Dispatchers.Main) {
                callback.onBitmap(bitmap)
            }

            Log.d(
                TAG,
                "[getCurrentLargeIcon] " +
                        "Обложка передана в уведомление, " +
                        "trackId=$trackId, " +
                        "size=${bitmap.width}x${bitmap.height}",
            )
        }

        /*
         * Уведомление создаётся сразу, а обложка
         * будет передана позже через callback.
         */
        return null
    }

    private companion object {
        const val TAG = "yPushMediaAdapter"

        const val UNKNOWN_TITLE = "Unknown"

        const val CONTENT_INTENT_REQUEST_CODE = 0
    }
}