package com.belotron.weatherradarhr

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.*
import android.content.SharedPreferences
import android.preference.PreferenceManager
import java.lang.Math.max

private const val KEY_LAST_RELOADED_TIMESTAMP = "last-reloaded-timestamp"
private const val KEY_LAST_PAUSED_TIMESTAMP = "last-paused-timestamp"
private const val KEY_FREEZE_TIME = "freeze_time_millis"
private const val KEY_ANIMATION_RATE = "animation_rate_mins_per_sec"
private const val KEY_WIDGET_LOG_ENABLED = "widget_log_enabled"

const val DEFAULT_ANIMATION_RATE = 85
const val DEFAULT_FREEZE_TIME = 1500

val Context.sharedPrefs: SharedPreferences get() = PreferenceManager.getDefaultSharedPreferences(this)

val Context.adControlPrefs: SharedPreferences get() = getSharedPreferences("ad_control", MODE_PRIVATE)

val Context.adsEnabled: Boolean get() = adControlPrefs.getBoolean(KEY_ADS_ENABLED, true)

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
