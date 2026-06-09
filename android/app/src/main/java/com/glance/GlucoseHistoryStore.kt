package com.glance

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class GlucoseHistoryStore(context: Context) :
  SQLiteOpenHelper(context, "glance_glucose.db", null, 1) {

  override fun onCreate(db: SQLiteDatabase) {
    db.execSQL(
      """
      CREATE TABLE glucose_readings (
        timestamp_millis INTEGER PRIMARY KEY,
        value INTEGER NOT NULL,
        trend TEXT,
        source TEXT NOT NULL
      )
      """.trimIndent(),
    )
  }

  override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

  fun upsert(readings: List<GlucoseReading>) {
    val db = writableDatabase
    db.beginTransaction()
    try {
      readings.forEach { reading ->
        val values = ContentValues().apply {
          put("timestamp_millis", reading.timestampMillis)
          put("value", reading.value)
          put("trend", reading.trend)
          put("source", reading.source)
        }
        db.insertWithOnConflict(
          "glucose_readings",
          null,
          values,
          SQLiteDatabase.CONFLICT_REPLACE,
        )
      }
      db.setTransactionSuccessful()
    } finally {
      db.endTransaction()
    }
  }

  fun latest(source: String? = null): GlucoseReading? {
    val where = if (source == null) "" else "WHERE source = ?"
    val args = source?.let { arrayOf(it) } ?: emptyArray()

    readableDatabase.rawQuery(
      """
      SELECT timestamp_millis, value, trend, source
      FROM glucose_readings
      $where
      ORDER BY timestamp_millis DESC
      LIMIT 1
      """.trimIndent(),
      args,
    ).use { cursor ->
      if (!cursor.moveToFirst()) return null
      return cursor.toReading()
    }
  }

  fun history(minutes: Int, source: String? = null): List<GlucoseReading> {
    val sourceWhere = if (source == null) "" else "WHERE source = ?"
    val sourceArgs = source?.let { arrayOf(it) } ?: emptyArray()
    val latestTimestamp = readableDatabase.rawQuery(
      "SELECT MAX(timestamp_millis) FROM glucose_readings $sourceWhere",
      sourceArgs,
    ).use { cursor ->
      if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
    }

    if (latestTimestamp <= 0L) {
      return emptyList()
    }

    val since = latestTimestamp - minutes.coerceIn(1, 240) * 60_000L

    val where = if (source == null) {
      "timestamp_millis >= ?"
    } else {
      "timestamp_millis >= ? AND source = ?"
    }
    val args = if (source == null) {
      arrayOf(since.toString())
    } else {
      arrayOf(since.toString(), source)
    }

    readableDatabase.rawQuery(
      """
      SELECT timestamp_millis, value, trend, source
      FROM glucose_readings
      WHERE $where
      ORDER BY timestamp_millis ASC
      """.trimIndent(),
      args,
    ).use { cursor ->
      val readings = mutableListOf<GlucoseReading>()
      while (cursor.moveToNext()) {
        readings.add(cursor.toReading())
      }
      return readings
    }
  }

  fun deleteOlderThan(cutoffMillis: Long) {
    writableDatabase.delete(
      "glucose_readings",
      "timestamp_millis < ?",
      arrayOf(cutoffMillis.toString()),
    )
  }

  fun clear() {
    writableDatabase.delete("glucose_readings", null, null)
  }

  private fun android.database.Cursor.toReading(): GlucoseReading =
    GlucoseReading(
      timestampMillis = getLong(0),
      value = getInt(1),
      trend = if (isNull(2)) null else getString(2),
      source = getString(3),
    )
}
