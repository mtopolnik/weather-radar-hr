package com.belotron.weatherradarhr

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.location.Location
import androidx.preference.PreferenceManager
import java.lang.Math.max

private const val KEY_LAST_RELOADED_TIMESTAMP = "last-reloaded-timestamp"
private const val KEY_FREEZE_TIME = "freeze_time_millis"
private const val KEY_ANIMATION_RATE = "animation_rate_mins_per_sec"
private const val KEY_ANIMATION_MINUTES = "animation_covers_minutes"
private const val KEY_WIDGET_LOG_ENABLED = "widget_log_enabled"
private const val KEY_SHOULD_SHOW_FG_LOCATION_NOTICE = "should_show_fg_location_notice"
private const val KEY_SHOULD_SHOW_BG_LOCATION_NOTICE = "should_show_bg_location_notice"
private const val KEY_SHOULD_ASK_ENABLE_LOCATION = "should_ask_to_enable_location"

private const val NAME_LOCAL_PREFS = "local"
private const val KEY_LOCATION_LATITUDE = "location_latitude"
private const val KEY_LOCATION_LONGITUDE = "location_longitude"
private const val KEY_LOCATION_TIMESTAMP = "location_timestamp"

const val DEFAULT_ANIMATION_RATE = 85
const val DEFAULT_ANIMATION_MINUTES = 90
const val DEFAULT_FREEZE_TIME = 1500
const val MIN_ANIMATION_RATE = 10
const val MIN_ANIMATION_MINUTES = 5
const val MIN_FREEZE_TIME = 100

val Context.mainPrefs: SharedPreferences get() = PreferenceManager.getDefaultSharedPreferences(this)

val Context.localPrefs: SharedPreferences get() = getSharedPreferences(NAME_LOCAL_PREFS, MODE_PRIVATE)

val SharedPreferences.rateMinsPerSec: Int get() =
    max(MIN_ANIMATION_RATE, getInt(KEY_ANIMATION_RATE, DEFAULT_ANIMATION_RATE))

val SharedPreferences.freezeTimeMillis: Int get() = max(MIN_FREEZE_TIME, getInt(KEY_FREEZE_TIME, DEFAULT_FREEZE_TIME))

val SharedPreferences.animationCoversMinutes: Int get() =
    max(MIN_ANIMATION_MINUTES, getInt(KEY_ANIMATION_MINUTES, DEFAULT_ANIMATION_MINUTES))

val SharedPreferences.lastReloadedTimestamp: Long get() = getLong(KEY_LAST_RELOADED_TIMESTAMP, 0L)
fun SharedPreferences.Editor.setLastReloadedTimestamp(value: Long): SharedPreferences.Editor =
        putLong(KEY_LAST_RELOADED_TIMESTAMP, value)

val SharedPreferences.widgetLogEnabled: Boolean get() = getBoolean(KEY_WIDGET_LOG_ENABLED, false)
fun SharedPreferences.Editor.setWidgetLogEnabled(value: Boolean): SharedPreferences.Editor =
        putBoolean(KEY_WIDGET_LOG_ENABLED, value)

val SharedPreferences.shouldShowFgLocationNotice: Boolean get() = getBoolean(KEY_SHOULD_SHOW_FG_LOCATION_NOTICE, true)
fun SharedPreferences.Editor.setShouldShowFgLocationNotice(value: Boolean): SharedPreferences.Editor =
        putBoolean(KEY_SHOULD_SHOW_FG_LOCATION_NOTICE, value)

val SharedPreferences.shouldShowBgLocationNotice: Boolean get() = getBoolean(KEY_SHOULD_SHOW_BG_LOCATION_NOTICE, true)
fun SharedPreferences.Editor.setShouldShowBgLocationNotice(value: Boolean): SharedPreferences.Editor =
        putBoolean(KEY_SHOULD_SHOW_BG_LOCATION_NOTICE, value)

val SharedPreferences.shouldAskToEnableLocation: Boolean get() = getBoolean(KEY_SHOULD_ASK_ENABLE_LOCATION, true)
fun SharedPreferences.Editor.setShouldAskToEnableLocation(value: Boolean): SharedPreferences.Editor =
        putBoolean(KEY_SHOULD_ASK_ENABLE_LOCATION, value)

val Context.storedLocation: Triple<Double, Double, Long> get() = with(localPrefs) {
    Triple(getFloat(KEY_LOCATION_LATITUDE, 0f).toDouble(),
            getFloat(KEY_LOCATION_LONGITUDE, 0f).toDouble(),
            getLong(KEY_LOCATION_TIMESTAMP, 0))
}
fun Context.storeLocation(location: Location) {
    localPrefs.applyUpdate {
        val (lat, lon) = location
        putFloat(KEY_LOCATION_LATITUDE, lat.toFloat())
        putFloat(KEY_LOCATION_LONGITUDE, lon.toFloat())
        putLong(KEY_LOCATION_TIMESTAMP, System.currentTimeMillis())
    }
}
fun Context.deleteLocation() {
    localPrefs.applyUpdate {
        remove(KEY_LOCATION_LATITUDE)
        remove(KEY_LOCATION_LONGITUDE)
        remove(KEY_LOCATION_TIMESTAMP)
    }
}

inline fun SharedPreferences.applyUpdate(block: SharedPreferences.Editor.() -> Unit) {
    with (edit()) {
        try {
            block()
        } finally {
            apply()
        }
    }
}
