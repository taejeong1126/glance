package com.glance

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

private const val MIN_GLUCOSE = 40f
private const val MAX_GLUCOSE = 400f
private const val STALE_AFTER_MILLIS = 15 * 60 * 1000L
private const val TREND_FALLBACK_WINDOW_MINUTES = 30

class GlucoseValueComplicationService : SuspendingComplicationDataSourceService() {
  override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData =
    buildData(latestFreshReading())

  override fun getPreviewData(type: ComplicationType): ComplicationData? =
    if (type == ComplicationType.SHORT_TEXT) {
      buildData(previewReading())
    } else {
      null
    }

  private fun buildData(reading: GlucoseReading?): ShortTextComplicationData {
    val displayText = reading?.value?.toString() ?: "--"
    val description = reading?.let { "혈당 ${it.value}" } ?: "혈당 데이터 없음"

    return ShortTextComplicationData.Builder(
      text = PlainComplicationText.Builder(displayText).build(),
      contentDescription = PlainComplicationText.Builder(description).build(),
    )
      .setTapAction(complicationTapAction())
      .build()
  }
}

class GlucoseTrendComplicationService : SuspendingComplicationDataSourceService() {
  override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData =
    buildData(latestFreshReading())

  override fun getPreviewData(type: ComplicationType): ComplicationData? =
    if (type == ComplicationType.SHORT_TEXT) {
      buildData(previewReading())
    } else {
      null
    }

  private fun buildData(reading: GlucoseReading?): ShortTextComplicationData {
    val arrow = reading?.let { trendArrow(it.trend) }.orEmpty()
    val displayText = arrow.ifEmpty { "--" }
    val description = reading?.let {
      "혈당 추세 ${trendDescription(it.trend)}"
    } ?: "혈당 추세 데이터 없음"

    return ShortTextComplicationData.Builder(
      text = PlainComplicationText.Builder(displayText).build(),
      contentDescription = PlainComplicationText.Builder(description).build(),
    )
      .setTapAction(complicationTapAction())
      .build()
  }
}

class GlucoseValueTrendComplicationService : SuspendingComplicationDataSourceService() {
  override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData =
    buildData(latestFreshReading())

  override fun getPreviewData(type: ComplicationType): ComplicationData? =
    if (type == ComplicationType.SHORT_TEXT) {
      buildData(previewReading())
    } else {
      null
    }

  private fun buildData(reading: GlucoseReading?): ShortTextComplicationData {
    val displayText = reading?.let {
      "${it.value}${trendArrow(it.trend)}"
    } ?: "--"
    val description = reading?.let {
      "혈당 ${it.value}, 추세 ${trendDescription(it.trend)}"
    } ?: "혈당 데이터 없음"

    return ShortTextComplicationData.Builder(
      text = PlainComplicationText.Builder(displayText).build(),
      contentDescription = PlainComplicationText.Builder(description).build(),
    )
      .setTapAction(complicationTapAction())
      .build()
  }
}

class GlucoseRangeComplicationService : SuspendingComplicationDataSourceService() {
  override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData =
    buildData(latestFreshReading())

  override fun getPreviewData(type: ComplicationType): ComplicationData? =
    if (type == ComplicationType.RANGED_VALUE) {
      buildData(previewReading())
    } else {
      null
    }

  private fun buildData(reading: GlucoseReading?): RangedValueComplicationData {
    val displayText = reading?.value?.toString() ?: "--"
    val description = reading?.let { "혈당 ${it.value}" } ?: "혈당 데이터 없음"
    val rangeValue = reading?.value?.toFloat()?.coerceIn(MIN_GLUCOSE, MAX_GLUCOSE)
      ?: MIN_GLUCOSE

    return RangedValueComplicationData.Builder(
      value = rangeValue,
      min = MIN_GLUCOSE,
      max = MAX_GLUCOSE,
      contentDescription = PlainComplicationText.Builder(description).build(),
    )
      .setMonochromaticImage(
        MonochromaticImage.Builder(
          Icon.createWithResource(this, R.drawable.ic_glucose_complication),
        ).build(),
      )
      .setTapAction(complicationTapAction())
      .setText(PlainComplicationText.Builder(displayText).build())
      .build()
  }
}

private fun SuspendingComplicationDataSourceService.latestFreshReading(): GlucoseReading? =
    GlucoseHistoryStore(this).use { store ->
      val source = currentConfiguredSource()
      val latest = store.latest(source)
      if (latest == null || System.currentTimeMillis() - latest.timestampMillis > STALE_AFTER_MILLIS) {
        ensureSyncServiceRunning()
        return@use null
      }
      val history = store.history(TREND_FALLBACK_WINDOW_MINUTES, source ?: latest.source)
      latest.withFallbackTrend(history)
    }

private fun SuspendingComplicationDataSourceService.complicationTapAction(): PendingIntent =
  PendingIntent.getActivity(
    this,
    0,
    Intent(this, MainActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    },
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
  )

private fun SuspendingComplicationDataSourceService.currentConfiguredSource(): String? =
  parseGlucoseConfig(
    getSharedPreferences(
      GlucoseForegroundService.PREFS_NAME,
      Context.MODE_PRIVATE,
    ).getString(GlucoseForegroundService.KEY_CONFIG_JSON, null),
  )?.sourceName()

private fun SuspendingComplicationDataSourceService.ensureSyncServiceRunning() {
  val hasConfig = !getSharedPreferences(
    GlucoseForegroundService.PREFS_NAME,
    Context.MODE_PRIVATE,
  ).getString(GlucoseForegroundService.KEY_CONFIG_JSON, null).isNullOrBlank()
  if (!hasConfig) {
    return
  }

  startForegroundService(
    Intent(this, GlucoseForegroundService::class.java).apply {
      action = GlucoseForegroundService.ACTION_ENSURE_RUNNING
    },
  )
}

private fun previewReading() =
  GlucoseReading(
    value = 123,
    timestampMillis = System.currentTimeMillis(),
    trend = "FortyFiveUp",
    source = "preview",
  )

private fun GlucoseReading.withFallbackTrend(history: List<GlucoseReading>): GlucoseReading {
  if (!trend.isNullOrBlank()) return this
  val inferredTrend = inferTrend(history) ?: return this
  return copy(trend = inferredTrend)
}

private fun inferTrend(history: List<GlucoseReading>): String? {
  if (history.size < 5) return null

  val recent = history.takeLast(5)
  val first = recent.first()
  val last = recent.last()
  val minutes = (last.timestampMillis - first.timestampMillis) / 60_000.0

  if (minutes <= 0.0) return null

  val deltaPerMinute = (last.value - first.value) / minutes

  return when {
    deltaPerMinute >= 3.0 -> "DoubleUp"
    deltaPerMinute >= 2.0 -> "SingleUp"
    deltaPerMinute >= 1.0 -> "FortyFiveUp"
    deltaPerMinute > -1.0 -> "Flat"
    deltaPerMinute > -2.0 -> "FortyFiveDown"
    deltaPerMinute > -3.0 -> "SingleDown"
    else -> "DoubleDown"
  }
}

private fun trendArrow(trend: String?): String =
  when (normalizeTrend(trend)) {
    "doubleup", "double_up", "rapidlyrising" -> "⏫"
    "singleup", "single_up", "rising" -> "⬆"
    "fortyfiveup", "forty_five_up", "45up", "risingquickly" -> "⬈"
    "flat", "steady" -> "➡"
    "fortyfivedown", "forty_five_down", "45down", "fallingquickly" -> "⬊"
    "singledown", "single_down", "falling" -> "⬇"
    "doubledown", "double_down", "rapidlyfalling" -> "⏬"
    else -> ""
  }

private fun normalizeTrend(trend: String?): String =
  trend
    ?.trim()
    ?.lowercase()
    ?.replace(" ", "")
    ?.replace("-", "")
    ?.replace("/", "")
    ?.replace("_", "_")
    ?: ""

private fun trendDescription(trend: String?): String =
  when (trendArrow(trend)) {
    "⏫" -> "빠르게 상승"
    "⬆" -> "상승"
    "⬈" -> "완만한 상승"
    "➡" -> "유지"
    "⬊" -> "완만한 하락"
    "⬇" -> "하락"
    "⏬" -> "빠르게 하락"
    else -> "알 수 없음"
  }
