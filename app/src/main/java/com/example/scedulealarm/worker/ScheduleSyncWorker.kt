package com.example.scedulealarm.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.scedulealarm.repository.ScheduleRepository
import com.example.scedulealarm.service.ScheduleForegroundService
import java.util.concurrent.TimeUnit

class ScheduleSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "Executing periodic schedule sync...")
        val repository = ScheduleRepository(applicationContext)

        val result = repository.fetchAndSaveSchedule()
        return if (result.isSuccess) {
            Log.d(TAG, "Periodic schedule sync succeeded.")
            if (repository.isServiceEnabled()) {
                ScheduleForegroundService.updateNotification(applicationContext)
            }
            Result.success()
        } else {
            Log.e(TAG, "Periodic schedule sync failed: ${result.exceptionOrNull()?.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ScheduleSyncWorker"
        private const val UNIQUE_WORK_NAME = "nsu_schedule_sync_work"

        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<ScheduleSyncWorker>(
                24, TimeUnit.HOURS,
                2, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
            Log.d(TAG, "Enqueued 24h periodic schedule sync work")
        }
    }
}
