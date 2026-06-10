package com.glance

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class GlucoseForegroundService : Service() {
  private var pollExecutor: ScheduledExecutorService? = null
  @Volatile private var syncGeneration = 0L

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    startForeground(NOTIFICATION_ID, buildNotification("혈당 동기화 실행 중"))

    stopSyncWorkers()

    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    val config = parseGlucoseConfig(prefs.getString(KEY_CONFIG_JSON, null))

    if (config is GlucoseConfig.XdripSync) {
      startXdripPolling(config)
    } else if (config != null) {
      pollExecutor = Executors.newSingleThreadScheduledExecutor()
      pollExecutor?.scheduleWithFixedDelay(
        { syncOnce() },
        0,
        DEFAULT_POLL_INTERVAL_SECONDS,
        TimeUnit.SECONDS,
      )
    }

    return START_STICKY
  }

  private fun startXdripPolling(config: GlucoseConfig.XdripSync) {
    XdripSyncClient.attachContext(this)
    val generation = syncGeneration
    pollExecutor = Executors.newSingleThreadScheduledExecutor()
    pollExecutor?.scheduleWithFixedDelay(
      { syncXdripOnce(config, generation) },
      0,
      XDRIP_POLL_INTERVAL_SECONDS,
      TimeUnit.SECONDS,
    )
  }

  private fun saveXdripReading(reading: GlucoseReading, generation: Long) {
    if (syncGeneration != generation) return

    val now = System.currentTimeMillis()
    if (reading.timestampMillis < now - XDRIP_MAX_READING_AGE_MS ||
      reading.timestampMillis > now + XDRIP_FUTURE_TOLERANCE_MS
    ) {
      return
    }

    GlucoseHistoryStore(this).use { store ->
      store.upsert(listOf(reading))
      store.deleteOlderThan(System.currentTimeMillis() - 6 * 60 * 60 * 1000L)
    }

    markSyncSuccessful()
    requestComplicationUpdates()
  }

  private fun syncXdripOnce(config: GlucoseConfig.XdripSync, generation: Long) {
    if (syncGeneration != generation || Thread.currentThread().isInterrupted) return

    try {
      val newerThanMillis = GlucoseHistoryStore(this).use { store ->
        store.latest("xdripSync")?.timestampMillis ?: 0L
      }
      val reading = XdripSyncClient.fetchLatestAfter(
        config = config,
        newerThanMillis = newerThanMillis,
        timeoutMs = XDRIP_SNAPSHOT_TIMEOUT_MS,
      )

      if (reading != null) {
        saveXdripReading(reading, generation)
      }
    } catch (error: Throwable) {
      if (syncGeneration == generation) {
        saveSyncError(error)
      }
    }
  }

  private fun syncOnce() {
    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    val config = parseGlucoseConfig(prefs.getString(KEY_CONFIG_JSON, null)) ?: return
    val store = GlucoseHistoryStore(this)

    try {
      val client = clientFor(config)
      val readings = client.fetchHistory(config, 240)

      if (readings.isNotEmpty()) {
        store.upsert(readings)
        store.deleteOlderThan(System.currentTimeMillis() - 6 * 60 * 60 * 1000L)
        requestComplicationUpdates()
        markSyncSuccessful()
      }
    } catch (error: Throwable) {
      saveSyncError(error)
    } finally {
      store.close()
    }
  }

  private fun markSyncSuccessful() {
    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
      .edit()
      .putString(KEY_XDRIP_DEBUG, "saved")
      .putString(KEY_LAST_ERROR, null)
      .putLong(KEY_LAST_SYNC_AT, System.currentTimeMillis())
      .apply()
  }

  private fun saveSyncError(error: Throwable) {
    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
      .edit()
      .putString(KEY_XDRIP_DEBUG, "error:${error.message ?: error.javaClass.simpleName}")
      .putString(KEY_LAST_ERROR, error.message ?: error.javaClass.simpleName)
      .putLong(KEY_LAST_SYNC_AT, System.currentTimeMillis())
      .apply()
  }

  private fun stopSyncWorkers() {
    syncGeneration += 1
    XdripSyncClient.closeStream()
    pollExecutor?.shutdownNow()
    pollExecutor = null
  }

  private fun requestComplicationUpdates() {
    listOf(
      GlucoseValueComplicationService::class.java,
      GlucoseTrendComplicationService::class.java,
      GlucoseValueTrendComplicationService::class.java,
      GlucoseRangeComplicationService::class.java,
    ).forEach { serviceClass ->
      ComplicationDataSourceUpdateRequester.create(
        this,
        ComponentName(this, serviceClass),
      ).requestUpdateAll()
    }
  }

  private fun buildNotification(text: String): Notification =
    Notification.Builder(this, CHANNEL_ID)
      .setContentTitle("Glance")
      .setContentText(text)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setOngoing(true)
      .build()

  private fun createNotificationChannel() {
    val channel = NotificationChannel(
      CHANNEL_ID,
      "Glucose Sync",
      NotificationManager.IMPORTANCE_LOW,
    )
    getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  override fun onDestroy() {
    stopSyncWorkers()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  companion object {
    private const val NOTIFICATION_ID = 1001
    const val CHANNEL_ID = "glucose_sync"
    const val PREFS_NAME = "glance_glucose"
    const val KEY_CONFIG_JSON = "config_json"
    const val KEY_LAST_ERROR = "last_error"
    const val KEY_LAST_SYNC_AT = "last_sync_at"
    const val KEY_XDRIP_DEBUG = "xdrip_debug"
    private const val DEFAULT_POLL_INTERVAL_SECONDS = 220L
    private const val XDRIP_POLL_INTERVAL_SECONDS = 100L
    private const val XDRIP_SNAPSHOT_TIMEOUT_MS = 20_000L
    private const val XDRIP_MAX_READING_AGE_MS = 6 * 60 * 60 * 1000L
    private const val XDRIP_FUTURE_TOLERANCE_MS = 2 * 60 * 1000L
  }
}
