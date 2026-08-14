package com.yellastrodev.dwij.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yellastrodev.dwij.R
import com.yellastrodev.dwij.activities.MainActivity
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.data.repo.LocalCatalogSyncResult
import com.yellastrodev.dwij.yApplication
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Последовательно уточняет внешнюю catalog-метадату локальных треков.
 * Foreground начинается только после обнаружения несовпавших хешей и авторизации ЯМ.
 */
class LocalCatalogResolveWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        resolvePendingTracks()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.e(TAG, "[doWork] Неожиданная ошибка online-resolve", error)
        Result.retry()
    }

    private suspend fun resolvePendingTracks(): Result {
        val component = (applicationContext as yApplication).component
        val synchronizer = component.localCatalogSynchronizer
        var pending = synchronizer.pendingTracks()
        if (pending.isEmpty()) {
            Log.d(TAG, "[resolvePendingTracks] Расхождений хешей нет")
            return Result.success()
        }
        if (component.yandexSessionManager.currentLogin() == null) {
            Log.d(TAG, "[resolvePendingTracks] Ожидается авторизация ЯМ, tracks=${pending.size}")
            return Result.failure()
        }

        createNotificationChannel()
        var completed = 0
        var total = pending.size
        Log.d(TAG, "[resolvePendingTracks] Начат online-resolve, tracks=$total")
        setForeground(foregroundInfo(completed, total))

        while (pending.isNotEmpty()) {
            pending.forEach { track ->
                if (isStopped) return Result.success()
                when (val result = synchronizer.resolve(track)) {
                    is DataResult.Success -> when (result.value) {
                        is LocalCatalogSyncResult.Applied -> {
                            completed++
                            if (
                                completed == total ||
                                completed % NOTIFICATION_PROGRESS_STEP == 0
                            ) {
                                setForeground(foregroundInfo(completed, total))
                            }
                        }
                        LocalCatalogSyncResult.StaleLocalMetadata -> Unit
                    }
                    is DataResult.Failure -> {
                        Log.e(
                            TAG,
                            "[resolvePendingTracks] Ошибка track=${track.instanceId}: ${result.error}",
                        )
                        return result.error.toWorkerResult()
                    }
                }
            }

            pending = synchronizer.pendingTracks()
            total = maxOf(total, completed + pending.size)
            if (pending.isNotEmpty()) {
                setForeground(foregroundInfo(completed, total))
            }
        }
        Log.d(TAG, "[resolvePendingTracks] Online-resolve завершён, обработано=$completed")
        return Result.success()
    }

    private fun DataError.toWorkerResult(): Result = when (this) {
        DataError.NoInternet,
        DataError.Timeout,
        is DataError.Network,
        is DataError.Storage -> Result.retry()

        is DataError.Remote -> if (statusCode == 429 || statusCode >= 500) {
            Result.retry()
        } else {
            Result.failure()
        }

        DataError.Unauthorized,
        is DataError.InvalidData,
        is DataError.NotFound,
        is DataError.Unknown -> Result.failure()
    }

    private fun foregroundInfo(completed: Int, total: Int): ForegroundInfo {
        val cancelIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_dance_monochrom)
            .setColor(Color.BLACK)
            .setContentTitle(applicationContext.getString(R.string.local_catalog_resolve_title))
            .setContentText(
                applicationContext.getString(
                    R.string.local_catalog_resolve_progress,
                    completed,
                    total,
                ),
            )
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setProgress(total.coerceAtLeast(1), completed.coerceAtMost(total), false)
            .addAction(
                android.R.drawable.ic_delete,
                applicationContext.getString(R.string.local_catalog_resolve_cancel),
                cancelIntent,
            )
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        applicationContext,
        0,
        Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.local_catalog_resolve_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = applicationContext.getString(
                    R.string.local_catalog_resolve_channel_description,
                )
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    companion object {
        private const val UNIQUE_WORK = "local-catalog-resolve"
        private const val TAG = "LocalCatalogResolve"
        private const val CHANNEL_ID = "local_catalog_resolve"
        private const val NOTIFICATION_ID = 4103
        private const val NOTIFICATION_PROGRESS_STEP = 10

        /** Ставит один следующий проход; цепочка не теряет изменения во время активной работы. */
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<LocalCatalogResolveWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }
    }
}
