package com.yellastrodev.dwij.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerNotificationManager
import com.yellastrodev.dwij.activities.MainActivity
import com.yellastrodev.yandexmusiclib.entities.CoverSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.collections.get

@UnstableApi
class yPushMediaAdapterobject(
    val playerService: PlayerService
) : PlayerNotificationManager.MediaDescriptionAdapter {

    val TAG = "yPushMediaAdapter"

    private var coverJob: Job? = null

    // Внутри PlayerService
    private val coverCache = LinkedHashMap<String, Bitmap>(5, 0.75f, true) // LRU с порядком доступа
    private val COVER_CACHE_LIMIT = 5

    override fun getCurrentContentTitle(player: Player): CharSequence {
        val title = player.currentMediaItem?.mediaMetadata?.title ?: "Unknown"
        Log.d(TAG, "getCurrentContentTitle: $title")
        return title
    }

    override fun createCurrentContentIntent(player: Player): PendingIntent? {
        Log.d(TAG, "createCurrentContentIntent called")
        return PendingIntent.getActivity(
            playerService,
            0,
            Intent(playerService, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun getCurrentContentText(player: Player): CharSequence? {
        val artist = player.currentMediaItem?.mediaMetadata?.artist
        Log.d(TAG, "getCurrentContentText: $artist")
        return artist
    }

    override fun getCurrentLargeIcon(
        player: Player,
        callback: PlayerNotificationManager.BitmapCallback
    ): Bitmap? {
        // Отменяем предыдущую загрузку
        coverJob?.cancel()
        val mediaItem = player.currentMediaItem
        val trackId = mediaItem?.mediaMetadata?.extras?.getString("track_id")
        Log.d(TAG, "getCurrentLargeIcon called: trackId=$trackId")

        // Проверяем кеш
        coverCache[trackId]?.let {
            Log.d(TAG, "Есть кеш кавера trackId=$trackId")
            callback.onBitmap(it)
            return it
        }

        coverJob = GlobalScope.launch {
            val bitmap = getCurrentTrackCoverBitmap(trackId!!)
            if (bitmap != null) {
                withContext(Dispatchers.Main) {
                    Log.d(TAG, "onBitmap callback отправлен для trackId=$trackId")
                    callback.onBitmap(bitmap)
                }
                // Добавляем в кеш с ограничением размера
                synchronized(coverCache) {
                    if (coverCache.size >= COVER_CACHE_LIMIT) {
                        val firstKey = coverCache.keys.first()
                        coverCache.remove(firstKey)
                    }
                    coverCache[trackId] = bitmap
                }
            } else {
                Log.d(TAG, "Cover bitmap null for trackId=$trackId")
            }
        }

        return null // уведомление подождёт callback
    }

    /**
     * Загружает обложку текущего трека через AlbumCoverRepository
     * @return Bitmap текущей обложки или null
     */
    private suspend fun getCurrentTrackCoverBitmap(trackId: String): Bitmap? {

        Log.d(TAG, "getCurrentTrackCoverBitmap: trackId=$trackId")
        val track = trackId?.let { playerService.trackRepo.getTrack(it) } ?: return null
        Log.d(TAG, "Track found in repo: $track")
        return playerService.coverRepo.getCover(track, CoverSize.`100x100`).also {
            Log.d(TAG, "Bitmap loaded for trackId=$trackId, size=${it.width}x${it.height}")
        }
    }
}