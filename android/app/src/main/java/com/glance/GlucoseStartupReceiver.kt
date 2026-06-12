package com.glance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class GlucoseStartupReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val receivedAction = intent?.action ?: return
    if (receivedAction != Intent.ACTION_BOOT_COMPLETED && receivedAction != Intent.ACTION_MY_PACKAGE_REPLACED) {
      return
    }

    val prefs = context.getSharedPreferences(
      GlucoseForegroundService.PREFS_NAME,
      Context.MODE_PRIVATE,
    )
    val hasConfig = !prefs.getString(GlucoseForegroundService.KEY_CONFIG_JSON, null).isNullOrBlank()
    if (!hasConfig) {
      return
    }

    GlucoseSyncWatchdogWorker.schedule(context)
    context.startForegroundService(
      Intent(context, GlucoseForegroundService::class.java).apply {
        action = GlucoseForegroundService.ACTION_ENSURE_RUNNING
      },
    )
  }
}
