package com.glance

import org.json.JSONArray
import org.json.JSONObject
import android.content.Context
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

interface GlucoseClient {
  fun fetchLatest(config: GlucoseConfig): GlucoseReading?
  fun fetchHistory(config: GlucoseConfig, minutes: Int): List<GlucoseReading> =
    listOfNotNull(fetchLatest(config))
}

object NightscoutClient : GlucoseClient {
  override fun fetchLatest(config: GlucoseConfig): GlucoseReading? =
    fetchHistory(config, 5).firstOrNull()

  override fun fetchHistory(config: GlucoseConfig, minutes: Int): List<GlucoseReading> {
    val nightscout = config as? GlucoseConfig.Nightscout ?: return emptyList()
    val count = ((minutes.coerceIn(1, 240) / 5) + 2).coerceAtLeast(1)
    val body = httpGet("${nightscout.url.trimEnd('/')}/api/v1/entries/sgv.json?count=$count")
    val entries = JSONArray(body)
    val readings = mutableListOf<GlucoseReading>()

    for (index in 0 until entries.length()) {
      val entry = entries.getJSONObject(index)
      val value = entry.optInt("sgv", 0)
      val timestamp = entry.optLong("date", 0L).takeIf { it > 0L }?.let(::normalizeEpochMillis)
        ?: parseTimestamp(entry.optString("dateString"))

      if (value > 0 && timestamp > 0L) {
        readings.add(
          GlucoseReading(
            value = value,
            timestampMillis = timestamp,
            trend = entry.optString("direction").ifBlank { null },
            source = "nightscout",
          ),
        )
      }
    }

    return readings.sortedBy { it.timestampMillis }
  }
}

object DexcomClient : GlucoseClient {
  private const val APPLICATION_ID = "d89443d2-327c-4a6f-89e5-496bbb0317db"
  private const val DEFAULT_UUID = "00000000-0000-0000-0000-000000000000"
  private const val BASE_URL = "https://shareous1.dexcom.com/ShareWebServices/Services"

  override fun fetchLatest(config: GlucoseConfig): GlucoseReading? =
    fetchHistory(config, 240).lastOrNull()

  override fun fetchHistory(config: GlucoseConfig, minutes: Int): List<GlucoseReading> {
    val dexcom = config as? GlucoseConfig.Dexcom ?: return emptyList()
    val sessionId = authenticate(dexcom.username, dexcom.password)
    val count = ((minutes.coerceIn(1, 240) / 5) + 2).coerceIn(1, 50)
    val requestBody = JSONObject()
      .put("sessionId", sessionId)
      .put("minutes", minutes.coerceIn(1, 1440))
      .put("maxCount", count)
    val requestQuery = mapOf(
      "sessionId" to sessionId,
      "minutes" to minutes.coerceIn(1, 1440).toString(),
      "maxCount" to count.toString(),
    )
    val readings = try {
      JSONArray(
        httpPostJson(
          "$BASE_URL/Publisher/ReadPublisherLatestGlucoseValues",
          requestBody,
        ),
      )
    } catch (postBodyError: Throwable) {
      try {
        JSONArray(
          httpPostForm(
            "$BASE_URL/Publisher/ReadPublisherLatestGlucoseValues",
            requestQuery,
          ),
        )
      } catch (_: Throwable) {
        try {
          JSONArray(
            httpGet(
              "$BASE_URL/Publisher/ReadPublisherLatestGlucoseValues?" +
                requestQuery.entries.joinToString("&") { "${it.key}=${it.value.urlEncode()}" },
            ),
          )
        } catch (_: Throwable) {
          throw IllegalStateException("dexcom_read_failed:${postBodyError.message ?: postBodyError.javaClass.simpleName}")
        }
      }
    }
    val result = mutableListOf<GlucoseReading>()

    for (index in 0 until readings.length()) {
      val item = readings.getJSONObject(index)
      val value = item.optInt("Value", 0)
      val timestamp = parseDexcomTimestamp(
        item.optString("DT").ifBlank {
          item.optString("ST").ifBlank { item.optString("WT") }
        },
      )

      if (value > 0 && timestamp > 0L) {
        result.add(
          GlucoseReading(
            value = value,
            timestampMillis = timestamp,
            trend = item.optString("Trend").ifBlank { null },
            source = "dexcom",
          ),
        )
      }
    }

    return result.sortedBy { it.timestampMillis }
  }

  private fun authenticate(username: String, password: String): String {
    val normalizedUsername = username.trim()
    val accountId = if (normalizedUsername.isUuid()) {
      normalizedUsername
    } else {
      try {
        httpPostJson(
          "$BASE_URL/General/AuthenticatePublisherAccount",
          JSONObject()
            .put("accountName", normalizedUsername)
            .put("applicationId", APPLICATION_ID)
            .put("password", password),
        ).trimJsonString()
      } catch (error: Throwable) {
        throw IllegalStateException("dexcom_auth_failed:${error.message ?: error.javaClass.simpleName}")
      }
    }

    if (accountId.isBlank() || accountId == DEFAULT_UUID) {
      throw IllegalStateException("dexcom_auth_failed")
    }

    val sessionId = try {
      httpPostJson(
        "$BASE_URL/General/LoginPublisherAccountById",
        JSONObject()
          .put("accountId", accountId)
          .put("applicationId", APPLICATION_ID)
          .put("password", password),
      ).trimJsonString()
    } catch (error: Throwable) {
      // Some Share accounts still respond better to the older ByName login.
      try {
        httpPostJson(
          "$BASE_URL/General/LoginPublisherAccountByName",
          JSONObject()
            .put("accountName", normalizedUsername)
            .put("applicationId", APPLICATION_ID)
            .put("password", password),
        ).trimJsonString()
      } catch (_: Throwable) {
        throw IllegalStateException("dexcom_session_failed:${error.message ?: error.javaClass.simpleName}")
      }
    }

    if (sessionId.isBlank() || sessionId == DEFAULT_UUID) {
      throw IllegalStateException("dexcom_session_failed")
    }

    return sessionId
  }
}

object XdripSyncClient : GlucoseClient {
  private const val HOST = "jamcm3749021.bluejay.website"
  private const val PORT = 25935
  private const val STATIC_KEY = "ebe5c0df162a50ba232d2d721ea8e3e1c5423bb0-12bd-48c3-8932-c93883dfcf1f"
  private const val READ_TIMEOUT_MS = 10 * 60 * 1000L
  private const val FRESH_READING_MS = 15 * 60 * 1000
  private const val TRANSPORT_SYNC_MSG = 2
  @Volatile private var activeSocket: Socket? = null

  override fun fetchLatest(config: GlucoseConfig): GlucoseReading? =
    fetchLatestAfter(config, 0L)

  fun fetchLatestAfter(config: GlucoseConfig, newerThanMillis: Long): GlucoseReading? {
    return fetchLatestAfter(config, newerThanMillis, READ_TIMEOUT_MS)
  }

  fun fetchLatestAfter(config: GlucoseConfig, newerThanMillis: Long, timeoutMs: Long): GlucoseReading? {
    var bestReading: GlucoseReading? = null
    val deadline = System.currentTimeMillis() + timeoutMs

    readStream(
      config = config,
      shouldContinue = { System.currentTimeMillis() < deadline },
      onReading = { reading ->
        if (reading.timestampMillis > newerThanMillis &&
          reading.timestampMillis > (bestReading?.timestampMillis ?: Long.MIN_VALUE)
        ) {
          bestReading = reading
        }

        reading.timestampMillis > newerThanMillis &&
          reading.timestampMillis >= System.currentTimeMillis() - FRESH_READING_MS
      },
    )

    return bestReading
  }

  fun stream(
    config: GlucoseConfig,
    shouldContinue: () -> Boolean,
    onReading: (GlucoseReading) -> Unit,
  ) {
    readStream(
      config = config,
      shouldContinue = shouldContinue,
      onReading = { reading ->
        onReading(reading)
        false
      },
    )
  }

  fun closeStream() {
    activeSocket?.runCatching { close() }
    activeSocket = null
  }

  private fun readStream(
    config: GlucoseConfig,
    shouldContinue: () -> Boolean,
    onReading: (GlucoseReading) -> Boolean,
  ) {
    val xdrip = config as? GlucoseConfig.XdripSync ?: return
    val identity = sha256(xdrip.groupKey).take(32)
    val driveKey = md5(xdrip.groupKey)
    val aesKey = md5Bytes(STATIC_KEY + driveKey)
    val token = sha256("glance-xdrip-cloud:${xdrip.groupKey}")
    val context = appContext ?: throw IllegalStateException("xdrip_context_missing")
    val prefs = context.getSharedPreferences(
      GlucoseForegroundService.PREFS_NAME,
      Context.MODE_PRIVATE,
    )

    fun setDebug(stage: String) {
      prefs.edit().putString(GlucoseForegroundService.KEY_XDRIP_DEBUG, stage).apply()
    }

    val socket = Socket(HOST, PORT)
    activeSocket = socket
    try {
      socket.use {
        setDebug("connected")
        socket.soTimeout = 30_000
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val buffer = ByteArrayOutputStream()
        var waitingBinary: Int? = null
        var lastHeader: Header? = null

        fun sendLine(line: String) {
          output.write("$line\n".toByteArray(Charsets.UTF_8))
          output.flush()
        }

        fun sendFrame(cmd: String, payload: ByteArray? = null, param: String? = null) {
          val size = payload?.size ?: 0
          val header = buildString {
            append(cmd)
            if (size > 0 || param != null) append(" ").append(size)
            if (param != null) append(" ").append(param)
          }
          sendLine(header)
          if (payload != null) {
            output.write(payload)
            output.flush()
          }
        }

        sendLine("1,1")
        sendLine(token)
        sendLine(identity)
        setDebug("identity_sent")

        val readBuffer = ByteArray(4096)
        while (shouldContinue()) {
          val count = try {
            input.read(readBuffer)
          } catch (_: SocketTimeoutException) {
            setDebug("read_timeout")
            continue
          }

          if (count < 0) throw IllegalStateException("xdrip_sync_closed")
          setDebug("data_chunk")
          buffer.write(readBuffer, 0, count)

          while (buffer.size() > 0) {
            val bytes = buffer.toByteArray()
            val binarySize = waitingBinary

            if (binarySize != null) {
              if (bytes.size < binarySize) break
              val binary = bytes.copyOfRange(0, binarySize)
              buffer.reset()
              buffer.write(bytes, binarySize, bytes.size - binarySize)
              waitingBinary = null

              val header = lastHeader
              setDebug("binary_${header?.cmd ?: "unknown"}")
              val readings = handleBinary(binary, header, aesKey)
              if (header?.cmd == "M") {
                sendFrame("N", param = header.param)
                setDebug("ack_sent")
              }
              lastHeader = null
              readings.forEach { reading ->
                setDebug("bgs_decoded")
                if (onReading(reading)) {
                  return
                }
              }
              continue
            }

            val newline = bytes.indexOf(0x0a)
            if (newline < 0) break

            val line = bytes.copyOfRange(0, newline).toString(Charsets.UTF_8)
            buffer.reset()
            buffer.write(bytes, newline + 1, bytes.size - newline - 1)

            if (line == "OK") {
              setDebug("ok")
              continue
            }

            val parts = line.split(" ")
            val cmd = parts.getOrNull(0).orEmpty()
            val size = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val param = parts.getOrNull(2)

            if (size > 0) {
              waitingBinary = size
              lastHeader = Header(cmd, param)
              setDebug("header_${cmd}_${size}")
              continue
            }

            if (cmd == "P") {
              sendFrame("O")
              setDebug("ping")
            }
            if (cmd == "O") {
              sendFrame("K")
              setDebug("pong")
            }
          }
        }
      }
    } finally {
      if (activeSocket === socket) {
        activeSocket = null
      }
    }
  }

  @Volatile
  private var appContext: Context? = null

  fun attachContext(context: Context) {
    appContext = context.applicationContext
  }

  private fun handleBinary(binary: ByteArray, header: Header?, aesKey: ByteArray): List<GlucoseReading> {
    if (header?.cmd != "M") return emptyList()

    val transport = decodeTransport(binary)
    if (transport.type != TRANSPORT_SYNC_MSG || transport.payload == null) return emptyList()

    val message = decodeSyncMsg(transport.payload)
    if (message.action != "bgs" || message.payload == null) return emptyList()
    return listOfNotNull(decodeBgsReading(message.payload, aesKey))
  }

  private fun decodeBgsReading(payload: ByteArray, aesKey: ByteArray): GlucoseReading? {
    val plain = decryptXdripPayload(payload, aesKey)
    val bg = JSONObject(plain.toString(Charsets.UTF_8))
    val value = bg.optDouble("calculated_value", 0.0)
    if (value <= 0.0) return null

    return GlucoseReading(
      value = Math.round(value).toInt(),
      timestampMillis = normalizeEpochMillis(bg.optLong("timestamp", System.currentTimeMillis())),
      trend = bg.optString("slope_name").ifBlank { null },
      source = "xdripSync",
    )
  }

  private data class Header(val cmd: String, val param: String?)
  private data class Transport(val type: Int?, val payload: ByteArray?)
  private data class SyncMessage(val action: String?, val payload: ByteArray?)

  private fun decodeTransport(buffer: ByteArray): Transport {
    var offset = 0
    var type: Int? = null
    var payload: ByteArray? = null

    while (offset < buffer.size) {
      val tag = readVarint(buffer, offset)
      val fieldNumber = tag.value shr 3
      val wireType = tag.value and 7
      offset = tag.offset

      if ((fieldNumber == 1 || fieldNumber == 2 || fieldNumber == 3) && wireType == 0) {
        val value = readVarint(buffer, offset)
        if (fieldNumber == 3) type = value.value
        offset = value.offset
      } else if (fieldNumber == 5 && wireType == 2) {
        val value = readLengthDelimited(buffer, offset)
        payload = value.bytes
        offset = value.offset
      } else {
        offset = skipField(buffer, wireType, offset)
      }
    }

    return Transport(type, payload)
  }

  private fun decodeSyncMsg(buffer: ByteArray): SyncMessage {
    var offset = 0
    var action: String? = null
    var payload: ByteArray? = null

    while (offset < buffer.size) {
      val tag = readVarint(buffer, offset)
      val fieldNumber = tag.value shr 3
      val wireType = tag.value and 7
      offset = tag.offset

      if (fieldNumber == 2 && wireType == 2) {
        val value = readLengthDelimited(buffer, offset)
        action = value.bytes.toString(Charsets.UTF_8)
        offset = value.offset
      } else if (fieldNumber == 3 && wireType == 2) {
        val value = readLengthDelimited(buffer, offset)
        payload = value.bytes
        offset = value.offset
      } else {
        offset = skipField(buffer, wireType, offset)
      }
    }

    return SyncMessage(action, payload)
  }

  private data class Varint(val value: Int, val offset: Int)
  private data class BytesResult(val bytes: ByteArray, val offset: Int)

  private fun readVarint(buffer: ByteArray, startOffset: Int): Varint {
    var value = 0
    var shift = 0
    var offset = startOffset

    while (offset < buffer.size) {
      val byte = buffer[offset].toInt() and 0xff
      value = value or ((byte and 0x7f) shl shift)
      offset += 1
      if ((byte and 0x80) == 0) return Varint(value, offset)
      shift += 7
    }

    throw IllegalStateException("invalid_varint")
  }

  private fun readLengthDelimited(buffer: ByteArray, offset: Int): BytesResult {
    val length = readVarint(buffer, offset)
    val start = length.offset
    val end = start + length.value
    return BytesResult(buffer.copyOfRange(start, end), end)
  }

  private fun skipField(buffer: ByteArray, wireType: Int, offset: Int): Int =
    when (wireType) {
      0 -> readVarint(buffer, offset).offset
      1 -> offset + 8
      2 -> {
        val length = readVarint(buffer, offset)
        length.offset + length.value
      }
      5 -> offset + 4
      else -> throw IllegalStateException("unsupported_wire_type_$wireType")
    }

  private fun decryptXdripPayload(payload: ByteArray, aesKey: ByteArray): ByteArray {
    if (payload.size < 17) throw IllegalStateException("payload_too_short")
    val iv = payload.copyOfRange(0, 16)
    val encrypted = payload.copyOfRange(16, payload.size)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
    return cipher.doFinal(encrypted)
  }
}

fun clientFor(config: GlucoseConfig): GlucoseClient =
  when (config) {
    is GlucoseConfig.Dexcom -> DexcomClient
    is GlucoseConfig.Nightscout -> NightscoutClient
    is GlucoseConfig.XdripSync -> XdripSyncClient
  }

private fun httpGet(url: String): String {
  val connection = URL(url).openConnection() as HttpURLConnection
  connection.requestMethod = "GET"
  connection.setRequestProperty("Accept", "application/json")
  connection.setRequestProperty("User-Agent", "Dexcom Share/3.0")
  connection.connectTimeout = 15_000
  connection.readTimeout = 25_000

  if (connection.responseCode !in 200..299) {
    throw IllegalStateException("http_${connection.responseCode}")
  }

  return connection.inputStream.bufferedReader().use { it.readText() }
}

private fun httpPostJson(url: String, body: JSONObject): String {
  val bytes = body.toString().toByteArray(Charsets.UTF_8)
  val connection = URL(url).openConnection() as HttpURLConnection
  connection.requestMethod = "POST"
  connection.setRequestProperty("Accept", "application/json")
  connection.setRequestProperty("Accept-Encoding", "application/json")
  connection.setRequestProperty("Content-Type", "application/json")
  connection.setRequestProperty("User-Agent", "Dexcom Share/3.0")
  connection.connectTimeout = 15_000
  connection.readTimeout = 25_000
  connection.doOutput = true
  connection.outputStream.use { it.write(bytes) }

  if (connection.responseCode !in 200..299) {
    throw IllegalStateException("http_${connection.responseCode}")
  }

  return connection.inputStream.bufferedReader().use { it.readText() }
}

private fun httpPostForm(url: String, params: Map<String, String>): String {
  val bytes = params.entries.joinToString("&") { "${it.key}=${it.value.urlEncode()}" }
    .toByteArray(Charsets.UTF_8)
  val connection = URL(url).openConnection() as HttpURLConnection
  connection.requestMethod = "POST"
  connection.setRequestProperty("Accept", "application/json")
  connection.setRequestProperty("Accept-Encoding", "application/json")
  connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
  connection.setRequestProperty("User-Agent", "Dexcom Share/3.0")
  connection.connectTimeout = 15_000
  connection.readTimeout = 25_000
  connection.doOutput = true
  connection.outputStream.use { it.write(bytes) }

  if (connection.responseCode !in 200..299) {
    throw IllegalStateException("http_${connection.responseCode}")
  }

  return connection.inputStream.bufferedReader().use { it.readText() }
}

private fun parseTimestamp(value: String): Long {
  if (value.isBlank()) return 0L
  return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(0L)
}

private fun normalizeEpochMillis(value: Long): Long =
  when {
    value <= 0L -> 0L
    value < 10_000_000_000L -> value * 1000L
    value > 10_000_000_000_000L -> value / 1000L
    else -> value
  }

private fun parseDexcomTimestamp(value: String): Long {
  val match = Regex("""Date\((\d+)(?:[+-]\d+)?\)""").find(value)
  if (match != null) return normalizeEpochMillis(match.groupValues[1].toLongOrNull() ?: 0L)
  return parseTimestamp(value)
}

private fun String.trimJsonString(): String =
  trim().removePrefix("\"").removeSuffix("\"")

private fun String.urlEncode(): String =
  java.net.URLEncoder.encode(this, "UTF-8")

private fun String.isUuid(): Boolean =
  Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(this)

private fun md5(value: String): String = digestHex("MD5", value.toByteArray(Charsets.UTF_8))
private fun md5Bytes(value: String): ByteArray = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
private fun sha256(value: String): String = digestHex("SHA-256", value.toByteArray(Charsets.UTF_8))

private fun digestHex(algorithm: String, bytes: ByteArray): String =
  MessageDigest.getInstance(algorithm)
    .digest(bytes)
    .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }

private fun ByteArray.indexOf(value: Int): Int {
  for (index in indices) {
    if ((this[index].toInt() and 0xff) == value) return index
  }
  return -1
}
