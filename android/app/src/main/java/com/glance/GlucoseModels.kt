package com.glance

import org.json.JSONObject

data class GlucoseReading(
  val value: Int,
  val timestampMillis: Long,
  val trend: String?,
  val source: String,
)

sealed class GlucoseConfig {
  data class Dexcom(val username: String, val password: String) : GlucoseConfig()
  data class Nightscout(val url: String) : GlucoseConfig()
  data class XdripSync(val groupKey: String) : GlucoseConfig()
}

fun parseGlucoseConfig(raw: String?): GlucoseConfig? {
  if (raw.isNullOrBlank()) return null

  val json = JSONObject(raw)

  return when (json.optString("source")) {
    "dexcom" -> GlucoseConfig.Dexcom(
      username = json.optString("username"),
      password = json.optString("password"),
    )
    "nightscout" -> GlucoseConfig.Nightscout(url = json.optString("url"))
    "xdripSync" -> GlucoseConfig.XdripSync(groupKey = json.optString("groupKey"))
    else -> null
  }
}

fun GlucoseConfig.sourceName(): String =
  when (this) {
    is GlucoseConfig.Dexcom -> "dexcom"
    is GlucoseConfig.Nightscout -> "nightscout"
    is GlucoseConfig.XdripSync -> "xdripSync"
  }
