package com.glance

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.IBinder
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class GlucoseForegroundService : Service() {
  private var pollExecutor: ScheduledExecutorService? = null
  @Volatile private var syncGeneration = 0L
  @Volatile private var xdripFailureCount = 0
  @Volatile private var xdripBackoffUntilMillis = 0L

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    val config = parseGlucoseConfig(prefs.getString(KEY_CONFIG_JSON, null))
    if (config == null) {
      GlucoseSyncWatchdogWorker.cancel(this)
      stopSelf()
      return START_NOT_STICKY
    }

    GlucoseSyncWatchdogWorker.schedule(this)

    val action = intent?.action
    if (action == ACTION_REFRESH_NOW) {
      startForeground(NOTIFICATION_ID, buildNotification("혈당 동기화 실행 중"))
      if (pollExecutor == null || shouldForceRestart()) {
        stopSyncWorkers()
        startSyncWorkers(config)
      }
      if (config !is GlucoseConfig.XdripSync) {
        triggerImmediateSync(config, ignoreBackoff = true)
      }
      return START_STICKY
    }

    if (action == ACTION_ENSURE_RUNNING && pollExecutor != null && !shouldForceRestart()) {
      if (config !is GlucoseConfig.XdripSync && shouldRunImmediateRecovery()) {
        triggerImmediateSync(config, ignoreBackoff = true)
      }
      return START_STICKY
    }

    startForeground(NOTIFICATION_ID, buildNotification("혈당 동기화 실행 중"))
    stopSyncWorkers()
    startSyncWorkers(config)

    return START_STICKY
  }

  private fun startSyncWorkers(config: GlucoseConfig) {
    if (config is GlucoseConfig.XdripSync) {
      startXdripPolling(config)
    } else {
      pollExecutor = Executors.newSingleThreadScheduledExecutor()
      pollExecutor?.scheduleWithFixedDelay(
        { syncOnce() },
        0,
        DEFAULT_POLL_INTERVAL_SECONDS,
        TimeUnit.SECONDS,
      )
    }
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
    if (syncGeneration != generation) {
      saveDebug("skip_generation")
      return
    }

    GlucoseHistoryStore(this).use { store ->
      val previousLatest = store.latest("xdripSync")
      store.upsert(listOf(reading))
      store.deleteOlderThan(System.currentTimeMillis() - 6 * 60 * 60 * 1000L)
      markSyncSuccessful()
      if (hasReadingChanged(previousLatest, reading)) {
        requestComplicationUpdates()
      } else {
        saveDebug("saved_same_timestamp")
      }
    }
  }

  private fun syncXdripOnce(config: GlucoseConfig.XdripSync, generation: Long, ignoreBackoff: Boolean = false) {
    if (syncGeneration != generation || Thread.currentThread().isInterrupted) return
    if (!ignoreBackoff && System.currentTimeMillis() < xdripBackoffUntilMillis) return

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
      } else {
        saveDebug("decoded_but_no_reading")
        markSyncSuccessful()
      }
    } catch (error: Throwable) {
      if (syncGeneration == generation) {
        scheduleXdripBackoff()
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
      val previousLatest = store.latest(config.sourceName())
      val readings = if (previousLatest == null) {
        client.fetchHistory(config, 240)
      } else {
        listOfNotNull(client.fetchLatest(config))
          .filter { latest -> hasReadingChanged(previousLatest, latest) }
      }

      if (readings.isNotEmpty()) {
        store.upsert(readings)
        store.deleteOlderThan(System.currentTimeMillis() - 6 * 60 * 60 * 1000L)
        val latestReading = readings.maxByOrNull { it.timestampMillis }
        if (latestReading != null && hasReadingChanged(previousLatest, latestReading)) {
          requestComplicationUpdates()
        }
      }
      markSyncSuccessful()
    } catch (error: Throwable) {
      saveSyncError(error)
    } finally {
      store.close()
    }
  }

  private fun markSyncSuccessful() {
    xdripFailureCount = 0
    xdripBackoffUntilMillis = 0L
    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
      .edit()
      .putString(KEY_XDRIP_DEBUG, "saved")
      .putString(KEY_LAST_ERROR, null)
      .putLong(KEY_LAST_SYNC_AT, System.currentTimeMillis())
      .putLong(KEY_LAST_SUCCESS_AT, System.currentTimeMillis())
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

  private fun saveDebug(value: String) {
    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
      .edit()
      .putString(KEY_XDRIP_DEBUG, value)
      .apply()
  }

  private fun stopSyncWorkers() {
    syncGeneration += 1
    xdripFailureCount = 0
    xdripBackoffUntilMillis = 0L
    XdripSyncClient.closeStream()
    pollExecutor?.shutdownNow()
    pollExecutor = null
  }

  private fun scheduleXdripBackoff() {
    xdripFailureCount = (xdripFailureCount + 1).coerceAtMost(MAX_XDRIP_BACKOFF_STEPS)
    val backoffSeconds = XDRIP_POLL_INTERVAL_SECONDS * (1L shl (xdripFailureCount - 1))
    xdripBackoffUntilMillis = System.currentTimeMillis() + backoffSeconds.coerceAtMost(MAX_XDRIP_BACKOFF_SECONDS) * 1000L
  }

  private fun hasReadingChanged(previous: GlucoseReading?, current: GlucoseReading): Boolean =
    previous?.timestampMillis != current.timestampMillis ||
      previous?.value != current.value ||
      previous?.trend != current.trend

  private fun shouldForceRestart(): Boolean {
    val lastSuccessAt = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
      .getLong(KEY_LAST_SUCCESS_AT, 0L)
    val backoffRemainingMillis = xdripBackoffUntilMillis - System.currentTimeMillis()
    return lastSuccessAt <= 0L ||
      System.currentTimeMillis() - lastSuccessAt > FORCE_RESTART_AFTER_MILLIS ||
      backoffRemainingMillis > MAX_ALLOWED_BACKOFF_REMAINING_MILLIS
  }

  private fun shouldRunImmediateRecovery(): Boolean {
    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    val lastSuccessAt = prefs.getLong(KEY_LAST_SUCCESS_AT, 0L)
    val lastRecoveryAt = prefs.getLong(KEY_LAST_RECOVERY_AT, 0L)
    val now = System.currentTimeMillis()
    return lastSuccessAt <= 0L ||
      now - lastSuccessAt > STALE_RECOVERY_AFTER_MILLIS &&
      now - lastRecoveryAt > MIN_RECOVERY_INTERVAL_MILLIS
  }

  private fun triggerImmediateSync(config: GlucoseConfig, ignoreBackoff: Boolean) {
    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
      .edit()
      .putLong(KEY_LAST_RECOVERY_AT, System.currentTimeMillis())
      .apply()

    val executor = pollExecutor ?: Executors.newSingleThreadScheduledExecutor().also {
      pollExecutor = it
    }
    val generation = syncGeneration
    executor.execute {
      when (config) {
        is GlucoseConfig.XdripSync -> syncXdripOnce(config, generation, ignoreBackoff = ignoreBackoff)
        else -> syncOnce()
      }
    }
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
    const val KEY_LAST_SUCCESS_AT = "last_success_at"
    const val KEY_LAST_RECOVERY_AT = "last_recovery_at"
    const val KEY_XDRIP_DEBUG = "xdrip_debug"
    const val ACTION_ENSURE_RUNNING = "com.glance.action.ENSURE_RUNNING"
    const val ACTION_REFRESH_NOW = "com.glance.action.REFRESH_NOW"
    private const val DEFAULT_POLL_INTERVAL_SECONDS = 300L
    private const val XDRIP_POLL_INTERVAL_SECONDS = 300L
    private const val XDRIP_SNAPSHOT_TIMEOUT_MS = XdripSyncClient.DEFAULT_SNAPSHOT_TIMEOUT_MS
    private const val MAX_XDRIP_BACKOFF_STEPS = 4
    private const val MAX_XDRIP_BACKOFF_SECONDS = 20L * 60L
    private const val FORCE_RESTART_AFTER_MILLIS = 10 * 60 * 1000L
    private const val MAX_ALLOWED_BACKOFF_REMAINING_MILLIS = 10 * 60 * 1000L
    private const val STALE_RECOVERY_AFTER_MILLIS = 15 * 60 * 1000L
    private const val MIN_RECOVERY_INTERVAL_MILLIS = 5 * 60 * 1000L
  }
}
