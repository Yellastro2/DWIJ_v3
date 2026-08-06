package com.yellastrodev.dwij.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yellastrodev.dwij.data.DataError
import com.yellastrodev.dwij.data.DataResult
import com.yellastrodev.dwij.yApplication
import java.util.concurrent.TimeUnit

class LocalLibrarySyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = when (
        val result =
            (applicationContext as yApplication)
                .component
                .localMusicRepository
                .synchronize(force = false)
    ) {
        is DataResult.Success -> Result.success()
        is DataResult.Failure -> when (result.error) {
            DataError.Unauthorized -> Result.success()
            else -> Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_WORK = "local-library-periodic-sync"
        private const val IMMEDIATE_WORK = "local-library-immediate-sync"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<LocalLibrarySyncWorker>(6, TimeUnit.HOURS).build(),
            )
        }

        fun enqueueImmediate(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LocalLibrarySyncWorker>().build(),
            )
        }
    }
}
