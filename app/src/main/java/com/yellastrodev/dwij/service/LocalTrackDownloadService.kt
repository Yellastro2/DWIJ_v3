package com.yellastrodev.dwij.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.activities.MainActivity
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.repo.LocalTrackDownloadProgress
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * Foreground-очередь постоянного сохранения ЯМ-треков.
 *
 * Одиночные треки и плейлисты приходят одной командой, после чего элементы
 * последовательно сохраняются с общим счётчиком и прогрессом текущего файла.
 */
class LocalTrackDownloadService : Service() {

    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val queueLock = Any()
    private val requests = ArrayDeque<DownloadRequest>()
    private val queuedTrackIds = mutableSetOf<String>()

    private var workerJob: Job? = null
    private var activeTrackId: String? = null
    private var completedCount = 0
    private var failedCount = 0
    private var totalCount = 0
    private var latestStartId = 0
    private var lastNotificationAt = 0L
    private var lastNotificationPercent = -1

    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    private val trackStorage by lazy {
        (application as yApplication).component.trackCacheRepo
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        latestStartId = maxOf(latestStartId, startId)
        startInForeground(waitingNotification())

        val newRequests = intent?.toDownloadRequests().orEmpty()
        if (newRequests.isEmpty()) {
            Log.w(TAG, "[onStartCommand] Получена команда без корректных trackId")
            stopAfterQueue()
            return START_NOT_STICKY
        }

        synchronized(queueLock) {
            if (activeTrackId == null && requests.isEmpty()) {
                completedCount = 0
                failedCount = 0
                totalCount = 0
            }
            newRequests.forEach { request ->
                if (activeTrackId != request.trackId && queuedTrackIds.add(request.trackId)) {
                    requests.addLast(request)
                    totalCount++
                }
            }
        }
        startWorkerIfNeeded()
        return START_REDELIVER_INTENT
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startWorkerIfNeeded() {
        if (workerJob?.isActive == true) return
        workerJob = serviceScope.launch {
            processQueue()
        }
    }

    private suspend fun processQueue() {
        var lastRequest: DownloadRequest? = null
        var lastSucceeded = false

        while (true) {
            var request = synchronized(queueLock) {
                requests.pollFirst()?.also { next ->
                    queuedTrackIds.remove(next.trackId)
                    activeTrackId = next.trackId
                }
            }
            if (request == null) {
                delay(QUEUE_IDLE_GRACE_MS)
                request = synchronized(queueLock) {
                    requests.pollFirst()?.also { next ->
                        queuedTrackIds.remove(next.trackId)
                        activeTrackId = next.trackId
                    }
                }
            }
            request ?: break

            lastRequest = request
            lastNotificationAt = 0L
            lastNotificationPercent = -1
            showProgressNotification(request, progress = null, force = true)

            lastSucceeded = try {
                when (
                    val result = trackStorage.saveLocally(
                        trackId = request.trackId,
                        onProgress = { progress ->
                            showProgressNotification(request, progress)
                        },
                    )
                ) {
                    is DataResult.Success -> true
                    is DataResult.Failure -> {
                        Log.e(
                            TAG,
                            "[processQueue] Не удалось сохранить trackId=${request.trackId}: " +
                                result.error,
                        )
                        false
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "[processQueue] Ошибка сохранения trackId=${request.trackId}",
                    error,
                )
                false
            }

            synchronized(queueLock) {
                activeTrackId = null
                if (lastSucceeded) {
                    completedCount++
                } else {
                    failedCount++
                }
            }
        }

        val finishedRequest = lastRequest
        if (finishedRequest != null) {
            val finalNotification = completionNotification(
                request = finishedRequest,
                succeeded = synchronized(queueLock) { failedCount == 0 },
            )
            stopForeground(STOP_FOREGROUND_DETACH)
            notificationManager.notify(NOTIFICATION_ID, finalNotification)
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        stopSelfResult(latestStartId)
    }

    private fun showProgressNotification(
        request: DownloadRequest,
        progress: LocalTrackDownloadProgress?,
        force: Boolean = false,
    ) {
        val percent = progress?.fraction
            ?.let { fraction -> (fraction * 100f).toInt().coerceIn(0, 100) }
            ?: -1
        val now = System.currentTimeMillis()
        if (
            !force &&
            percent == lastNotificationPercent &&
            now - lastNotificationAt < NOTIFICATION_UPDATE_INTERVAL_MS
        ) {
            return
        }
        if (
            !force &&
            percent != 100 &&
            now - lastNotificationAt < NOTIFICATION_UPDATE_INTERVAL_MS
        ) {
            return
        }

        lastNotificationAt = now
        lastNotificationPercent = percent
        notificationManager.notify(
            NOTIFICATION_ID,
            progressNotification(request, percent),
        )
    }

    private fun progressNotification(
        request: DownloadRequest,
        percent: Int,
    ): Notification {
        val (completed, total) = synchronized(queueLock) {
            completedCount to totalCount.coerceAtLeast(1)
        }
        val countText = getString(
            R.string.local_track_download_count,
            completed,
            total,
        )
        val progressText = if (percent >= 0) {
            getString(R.string.local_track_download_progress, percent, countText)
        } else {
            countText
        }

        return baseNotificationBuilder()
            .setContentTitle(getString(R.string.local_track_download_notification_title))
            .setContentText(request.title)
            .setSubText(progressText)
            .setOngoing(true)
            .setProgress(
                if (percent >= 0) 100 else 0,
                percent.coerceAtLeast(0),
                percent < 0,
            )
            .build()
    }

    private fun waitingNotification(): Notification =
        baseNotificationBuilder()
            .setContentTitle(getString(R.string.local_track_download_notification_title))
            .setContentText(getString(R.string.local_track_download_preparing))
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

    private fun completionNotification(
        request: DownloadRequest,
        succeeded: Boolean,
    ): Notification {
        val (completed, total) = synchronized(queueLock) {
            completedCount to totalCount.coerceAtLeast(1)
        }
        return baseNotificationBuilder()
            .setContentTitle(
                getString(
                    if (succeeded) {
                        R.string.local_track_download_complete
                    } else {
                        R.string.local_track_download_failed
                    },
                ),
            )
            .setContentText(request.title)
            .setSubText(
                getString(
                    R.string.local_track_download_count,
                    completed,
                    total,
                ),
            )
            .setOngoing(false)
            .setAutoCancel(true)
            .setProgress(0, 0, false)
            .build()
    }

    private fun baseNotificationBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_dance_monochrom)
            .setColor(Color.BLACK)
            .setContentIntent(contentIntent())
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    private fun contentIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.local_track_download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.local_track_download_channel_description)
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun stopAfterQueue() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(latestStartId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.e(TAG, "[onTimeout] Android остановил dataSync foreground service")
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun Intent.toDownloadRequests(): List<DownloadRequest> {
        return when (action) {
            ACTION_ENQUEUE_TRACK -> {
                val trackId = getStringExtra(EXTRA_TRACK_ID)
                    ?.takeIf(String::isNotBlank)
                    ?: return emptyList()
                val title = getStringExtra(EXTRA_TRACK_TITLE)
                    ?.takeIf(String::isNotBlank)
                    ?: trackId
                listOf(DownloadRequest(trackId = trackId, title = title))
            }

            ACTION_ENQUEUE_TRACKS -> {
                val trackIds = getStringArrayListExtra(EXTRA_TRACK_IDS).orEmpty()
                val titles = getStringArrayListExtra(EXTRA_TRACK_TITLES).orEmpty()
                trackIds.mapIndexedNotNull { index, trackId ->
                    trackId.takeIf(String::isNotBlank)?.let { validTrackId ->
                        DownloadRequest(
                            trackId = validTrackId,
                            title = titles.getOrNull(index)
                                ?.takeIf(String::isNotBlank)
                                ?: validTrackId,
                        )
                    }
                }
            }

            else -> emptyList()
        }
    }

    private data class DownloadRequest(
        val trackId: String,
        val title: String,
    )

    companion object {
        private const val TAG = "LocalTrackDownload"
        private const val CHANNEL_ID = "local_track_downloads"
        private const val NOTIFICATION_ID = 4102
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 300L
        private const val QUEUE_IDLE_GRACE_MS = 350L
        private const val ACTION_ENQUEUE_TRACK =
            "com.yellastrodev.dwij.action.ENQUEUE_LOCAL_TRACK"
        private const val ACTION_ENQUEUE_TRACKS =
            "com.yellastrodev.dwij.action.ENQUEUE_LOCAL_TRACKS"
        private const val EXTRA_TRACK_ID = "track_id"
        private const val EXTRA_TRACK_TITLE = "track_title"
        private const val EXTRA_TRACK_IDS = "track_ids"
        private const val EXTRA_TRACK_TITLES = "track_titles"

        /** Запускается непосредственно из пользовательского клика в видимом UI. */
        fun enqueue(
            context: Context,
            trackId: String,
            title: String,
        ) {
            enqueueAll(context, listOf(trackId to title))
        }

        /** Одной foreground-командой ставит подготовленный плейлист в очередь. */
        fun enqueueAll(
            context: Context,
            tracks: List<Pair<String, String>>,
        ) {
            val uniqueTracks = tracks
                .filter { (trackId, _) -> trackId.isNotBlank() }
                .distinctBy { (trackId, _) -> trackId }
            if (uniqueTracks.isEmpty()) return
            val intent = Intent(context, LocalTrackDownloadService::class.java).apply {
                action = ACTION_ENQUEUE_TRACKS
                putStringArrayListExtra(
                    EXTRA_TRACK_IDS,
                    ArrayList(uniqueTracks.map { (trackId, _) -> trackId }),
                )
                putStringArrayListExtra(
                    EXTRA_TRACK_TITLES,
                    ArrayList(uniqueTracks.map { (_, title) -> title }),
                )
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
