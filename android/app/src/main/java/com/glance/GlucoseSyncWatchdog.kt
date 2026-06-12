package com.glance

import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class GlucoseSyncWatchdogWorker(
  appContext: Context,
  workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {

  override fun doWork(): Result {
    if (!hasConfig(applicationContext)) {
      cancel(applicationContext)
      return Result.success()
    }

    applicationContext.startForegroundService(
      Intent(applicationContext, GlucoseForegroundService::class.java).apply {
        action = GlucoseForegroundService.ACTION_ENSURE_RUNNING
      },
    )
    return Result.success()
  }

  companion object {
    private const val UNIQUE_WORK_NAME = "glucose-sync-watchdog"
    private const val WATCHDOG_INTERVAL_MINUTES = 15L

    fun schedule(context: Context) {
      if (!hasConfig(context)) {
        cancel(context)
        return
      }

      val request = PeriodicWorkRequestBuilder<GlucoseSyncWatchdogWorker>(
        WATCHDOG_INTERVAL_MINUTES,
        TimeUnit.MINUTES,
      ).build()

      WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        UNIQUE_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        request,
      )
    }

    fun cancel(context: Context) {
      WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    private fun hasConfig(context: Context): Boolean =
      !context.getSharedPreferences(
        GlucoseForegroundService.PREFS_NAME,
        Context.MODE_PRIVATE,
      ).getString(GlucoseForegroundService.KEY_CONFIG_JSON, null).isNullOrBlank()
  }
}
