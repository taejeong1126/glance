package com.glance

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

private const val MIN_GLUCOSE = 40f
private const val MAX_GLUCOSE = 400f
private const val STALE_AFTER_MILLIS = 15 * 60 * 1000L

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
    ).build()
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
    ).build()
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
    ).build()
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
      .setText(PlainComplicationText.Builder(displayText).build())
      .build()
  }
}

private fun SuspendingComplicationDataSourceService.latestFreshReading(): GlucoseReading? =
    GlucoseHistoryStore(this).use { store ->
      store.latest()?.takeIf {
        System.currentTimeMillis() - it.timestampMillis <= STALE_AFTER_MILLIS
      }
    }

private fun previewReading() =
  GlucoseReading(
    value = 123,
    timestampMillis = System.currentTimeMillis(),
    trend = "FortyFiveUp",
    source = "preview",
  )

private fun trendArrow(trend: String?): String =
  when (trend?.lowercase()) {
    "doubleup", "double_up", "rapidlyrising" -> "⇈"
    "singleup", "single_up", "rising" -> "↑"
    "fortyfiveup", "forty_five_up", "risingquickly" -> "↗"
    "flat", "steady" -> "→"
    "fortyfivedown", "forty_five_down", "fallingquickly" -> "↘"
    "singledown", "single_down", "falling" -> "↓"
    "doubledown", "double_down", "rapidlyfalling" -> "⇊"
    else -> ""
  }

private fun trendDescription(trend: String?): String =
  when (trendArrow(trend)) {
    "⇈" -> "빠르게 상승"
    "↑" -> "상승"
    "↗" -> "완만한 상승"
    "→" -> "유지"
    "↘" -> "완만한 하락"
    "↓" -> "하락"
    "⇊" -> "빠르게 하락"
    else -> "알 수 없음"
  }
