package com.glance

import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.view.WindowManager
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableNativeArray
import com.facebook.react.bridge.WritableNativeMap
import kotlin.concurrent.thread

class GlucoseSyncModule(
  private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {

  override fun getName(): String = "GlucoseSync"

  @ReactMethod
  fun saveConfig(configJson: String) {
    reactContext
      .getSharedPreferences(GlucoseForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .putString(GlucoseForegroundService.KEY_CONFIG_JSON, configJson)
      .apply()
  }

  @ReactMethod
  fun start() {
    val intent = Intent(reactContext, GlucoseForegroundService::class.java)
    reactContext.startForegroundService(intent)
  }

  @ReactMethod
  fun stop() {
    reactContext.stopService(Intent(reactContext, GlucoseForegroundService::class.java))
  }

  @ReactMethod
  fun clear() {
    stop()
    reactContext
      .getSharedPreferences(GlucoseForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
      .edit()
      .clear()
      .apply()
    GlucoseHistoryStore(reactContext).use { it.clear() }
  }

  @ReactMethod
  fun refreshNow(promise: Promise) {
    thread(name = "glucose-refresh") {
      try {
        val prefs = reactContext.getSharedPreferences(
          GlucoseForegroundService.PREFS_NAME,
          Context.MODE_PRIVATE,
        )
        val config = parseGlucoseConfig(
          prefs.getString(GlucoseForegroundService.KEY_CONFIG_JSON, null),
        ) ?: throw IllegalStateException("missing_config")

        val readings = GlucoseHistoryStore(reactContext).use { store ->
          val previousLatest = store.latest(config.sourceName())
          val fetched = if (previousLatest == null) {
            clientFor(config).fetchHistory(config, 240)
          } else {
            listOfNotNull(clientFor(config).fetchLatest(config))
              .filter { latest -> hasReadingChanged(previousLatest, latest) }
          }

          if (fetched.isNotEmpty()) {
            store.upsert(fetched)
            store.deleteOlderThan(System.currentTimeMillis() - 6 * 60 * 60 * 1000L)
            val latestReading = fetched.maxByOrNull { it.timestampMillis }
            if (latestReading != null && hasReadingChanged(previousLatest, latestReading)) {
              requestComplicationUpdates()
            }
          }

          fetched
        }

        prefs.edit()
          .putString(GlucoseForegroundService.KEY_LAST_ERROR, null)
          .putLong(GlucoseForegroundService.KEY_LAST_SYNC_AT, System.currentTimeMillis())
          .apply()

        promise.resolve(readings.size.toDouble())
      } catch (error: Throwable) {
        reactContext.getSharedPreferences(
          GlucoseForegroundService.PREFS_NAME,
          Context.MODE_PRIVATE,
        ).edit()
          .putString(GlucoseForegroundService.KEY_LAST_ERROR, error.message ?: error.javaClass.simpleName)
          .putLong(GlucoseForegroundService.KEY_LAST_SYNC_AT, System.currentTimeMillis())
          .apply()
        promise.reject("glucose_refresh_failed", error)
      }
    }
  }

  @ReactMethod
  fun getLatest(promise: Promise) {
    try {
      val source = currentSource()
      val reading = GlucoseHistoryStore(reactContext).use { it.latest(source) }
      promise.resolve(reading?.toMap())
    } catch (error: Throwable) {
      promise.reject("glucose_latest_failed", error)
    }
  }

  @ReactMethod
  fun getConfig(promise: Promise) {
    try {
      val prefs = reactContext.getSharedPreferences(
        GlucoseForegroundService.PREFS_NAME,
        Context.MODE_PRIVATE,
      )
      val config = parseGlucoseConfig(
        prefs.getString(GlucoseForegroundService.KEY_CONFIG_JSON, null),
      ) ?: run {
        promise.resolve(null)
        return
      }

      promise.resolve(
        WritableNativeMap().apply {
          putString("source", config.sourceName())
          when (config) {
            is GlucoseConfig.Dexcom -> {
              putString("username", config.username)
              putString("password", config.password)
            }
            is GlucoseConfig.Nightscout -> {
              putString("url", config.url)
            }
            is GlucoseConfig.XdripSync -> {
              putString("groupKey", config.groupKey)
            }
          }
        },
      )
    } catch (error: Throwable) {
      promise.reject("glucose_config_failed", error)
    }
  }

  @ReactMethod
  fun getHistory(minutes: Double, promise: Promise) {
    try {
      val source = currentSource()
      val readings = GlucoseHistoryStore(reactContext).use { it.history(minutes.toInt(), source) }
      val array = WritableNativeArray()
      readings.forEach { array.pushMap(it.toMap()) }
      promise.resolve(array)
    } catch (error: Throwable) {
      promise.reject("glucose_history_failed", error)
    }
  }

  @ReactMethod
  fun getStatus(promise: Promise) {
    val prefs = reactContext.getSharedPreferences(
      GlucoseForegroundService.PREFS_NAME,
      Context.MODE_PRIVATE,
    )
    promise.resolve(
      WritableNativeMap().apply {
        putDouble("lastSyncAt", prefs.getLong(GlucoseForegroundService.KEY_LAST_SYNC_AT, 0L).toDouble())
        putString("lastError", prefs.getString(GlucoseForegroundService.KEY_LAST_ERROR, null))
        putString("xdripDebug", prefs.getString(GlucoseForegroundService.KEY_XDRIP_DEBUG, null))
      },
    )
  }

  @ReactMethod
  fun enableKeepScreenOn() {
    val activity = reactContext.currentActivity ?: return
    activity.runOnUiThread {
      activity.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
  }

  @ReactMethod
  fun disableKeepScreenOn() {
    val activity = reactContext.currentActivity ?: return
    activity.runOnUiThread {
      activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
  }

  private fun GlucoseReading.toMap(): WritableNativeMap =
    WritableNativeMap().apply {
      putInt("value", value)
      putDouble("timestampMillis", timestampMillis.toDouble())
      if (trend == null) {
        putNull("trend")
      } else {
        putString("trend", trend)
      }
      putString("source", source)
    }

  private fun currentSource(): String? {
    val prefs = reactContext.getSharedPreferences(
      GlucoseForegroundService.PREFS_NAME,
      Context.MODE_PRIVATE,
    )

    return parseGlucoseConfig(prefs.getString(GlucoseForegroundService.KEY_CONFIG_JSON, null))
      ?.sourceName()
  }

  private fun requestComplicationUpdates() {
    listOf(
      GlucoseValueComplicationService::class.java,
      GlucoseTrendComplicationService::class.java,
      GlucoseValueTrendComplicationService::class.java,
      GlucoseRangeComplicationService::class.java,
    ).forEach { serviceClass ->
      ComplicationDataSourceUpdateRequester.create(
        reactContext,
        ComponentName(reactContext, serviceClass),
      ).requestUpdateAll()
    }
  }

  private fun hasReadingChanged(previous: GlucoseReading?, current: GlucoseReading): Boolean =
    previous?.timestampMillis != current.timestampMillis ||
      previous?.value != current.value ||
      previous?.trend != current.trend
}
