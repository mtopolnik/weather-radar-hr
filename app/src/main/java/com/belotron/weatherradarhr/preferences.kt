package com.belotron.weatherradarhr

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.text.format.DateUtils.HOUR_IN_MILLIS
import androidx.preference.PreferenceManager
import com.belotron.weatherradarhr.CcOption.CC_PRIVATE
import java.lang.Math.max
import java.lang.System.currentTimeMillis
import java.text.DateFormat
import java.text.SimpleDateFormat

private const val KEY_LAST_RELOADED_TIMESTAMP = "last-reloaded-timestamp"
private const val KEY_LAST_PAUSED_TIMESTAMP = "last-paused-timestamp"
private const val KEY_FREEZE_TIME = "freeze_time_millis"
private const val KEY_ANIMATION_RATE = "animation_rate_mins_per_sec"
private const val KEY_WIDGET_LOG_ENABLED = "widget_log_enabled"
private const val KEY_LOCATION_LATITUDE = "location_latitude"
private const val KEY_LOCATION_LONGITUDE = "location_longitude"
private const val KEY_LOCATION_TIMESTAMP = "location_timestamp"

const val DEFAULT_ANIMATION_RATE = 85
const val DEFAULT_FREEZE_TIME = 1500

val Context.sharedPrefs: SharedPreferences get() = PreferenceManager.getDefaultSharedPreferences(this)

val SharedPreferences.rateMinsPerSec: Int get() = max(1, getInt(KEY_ANIMATION_RATE, DEFAULT_ANIMATION_RATE))

val SharedPreferences.freezeTimeMillis: Int get() = getInt(KEY_FREEZE_TIME, DEFAULT_FREEZE_TIME)

val SharedPreferences.lastReloadedTimestamp: Long get() = getLong(KEY_LAST_RELOADED_TIMESTAMP, 0L)
fun SharedPreferences.Editor.setLastReloadedTimestamp(value: Long): SharedPreferences.Editor =
        putLong(KEY_LAST_RELOADED_TIMESTAMP, value)

val SharedPreferences.lastPausedTimestamp: Long get() = getLong(KEY_LAST_PAUSED_TIMESTAMP, 0L)
fun SharedPreferences.Editor.setLastPausedTimestamp(value: Long): SharedPreferences.Editor =
        putLong(KEY_LAST_PAUSED_TIMESTAMP, value)

val SharedPreferences.widgetLogEnabled: Boolean get() = getBoolean(KEY_WIDGET_LOG_ENABLED, false)
fun SharedPreferences.Editor.setWidgetLogEnabled(value: Boolean): SharedPreferences.Editor =
        putBoolean(KEY_WIDGET_LOG_ENABLED, value)

val SharedPreferences.location: Pair<Double, Double> get() =
    if (currentTimeMillis() - getLong(KEY_LOCATION_TIMESTAMP, 0) < HOUR_IN_MILLIS)
        Pair(getFloat(KEY_LOCATION_LATITUDE, 0f).toDouble(), getFloat(KEY_LOCATION_LONGITUDE, 0f).toDouble())
    else run {
        val df = DateFormat.getDateTimeInstance()
        val storedTime = df.format(getLong(KEY_LOCATION_TIMESTAMP, Long.MIN_VALUE))
        val currTime = df.format(currentTimeMillis())
        warn(CC_PRIVATE) { "Stored location is too old: $storedTime. Current time: $currTime" }
        Pair(0.0, 0.0)
    }

fun SharedPreferences.Editor.setLocation(location: Location): SharedPreferences.Editor {
    val (lat, lon) = location
    putFloat(KEY_LOCATION_LATITUDE, lat.toFloat())
    putFloat(KEY_LOCATION_LONGITUDE, lon.toFloat())
    putLong(KEY_LOCATION_TIMESTAMP, location.time)
    return this
}

@SuppressLint("CommitPrefEdits")
inline fun SharedPreferences.commitUpdate(block: SharedPreferences.Editor.() -> Unit) {
    with (edit()) {
        block()
        commit()
    }
}

@SuppressLint("CommitPrefEdits")
inline fun SharedPreferences.applyUpdate(block: SharedPreferences.Editor.() -> Unit) {
    with (edit()) {
        try {
            block()
        } finally {
            apply()
        }
    }
}
