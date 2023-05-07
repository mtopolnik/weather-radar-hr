/*
 * Copyright (C) 2018-2023 Marko Topolnik
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.belotron.weatherradarhr

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.location.Location
import androidx.preference.PreferenceManager

const val DEFAULT_ANIMATION_RATE = 85
const val MIN_ANIMATION_RATE = 10
const val DEFAULT_ANIMATION_MINUTES = 90
const val MIN_ANIMATION_MINUTES = 5
const val DEFAULT_FREEZE_TIME = 1500
const val MIN_FREEZE_TIME = 100
const val NEW_RADAR_INDICATOR_CURRENT_ID = 2


private const val KEY_FREEZE_TIME = "freeze_time_millis"
private const val KEY_ANIMATION_RATE = "animation_rate_mins_per_sec"
private const val KEY_ANIMATION_MINUTES = "animation_covers_minutes"
private const val KEY_SEEKBAR_VIBRATE = "seekbar_vibrate"
private const val KEY_RADAR_SOURCES = "radar_sources"
private const val KEY_WIDGET_LOG_ENABLED = "widget_log_enabled"
private const val KEY_SHOULD_SHOW_FG_LOCATION_NOTICE = "should_show_fg_location_notice"
private const val KEY_SHOULD_SHOW_BG_LOCATION_NOTICE = "should_show_bg_location_notice"
private const val KEY_SHOULD_ASK_ENABLE_LOCATION = "should_ask_to_enable_location"
private const val KEY_NEW_RADAR_INDICATOR_CONSUMED_ID = "new_radar_indicator_consumed_id"

private const val NAME_LOCAL_PREFS = "local"
private const val KEY_LOCATION_LATITUDE = "location_latitude"
private const val KEY_LOCATION_LONGITUDE = "location_longitude"
private const val KEY_LOCATION_TIMESTAMP = "location_timestamp"

private const val RADAR_SOURCE_DIVIDER = "DIVIDER"
private val DEFAULT_RADAR_SOURCES: Set<String> = run {
    val enabledSources = listOf(AnimationSource.HR_KOMPOZIT, AnimationSource.AT_ZAMG)
    val availableSources = AnimationSource.values().toList().filter { !enabledSources.contains(it) }
    enabledSources.plus(null).plus(availableSources).toStringSet()
}

val Context.mainPrefs: SharedPreferences get() = PreferenceManager.getDefaultSharedPreferences(this)

val Context.localPrefs: SharedPreferences get() = getSharedPreferences(NAME_LOCAL_PREFS, MODE_PRIVATE)

val SharedPreferences.rateMinsPerSec: Int get() =
    MIN_ANIMATION_RATE.coerceAtLeast(getInt(KEY_ANIMATION_RATE, DEFAULT_ANIMATION_RATE))

val SharedPreferences.freezeTimeMillis: Int get() = MIN_FREEZE_TIME.coerceAtLeast(
    getInt(KEY_FREEZE_TIME, DEFAULT_FREEZE_TIME))

val SharedPreferences.animationCoversMinutes: Int get() = 120
//    MIN_ANIMATION_MINUTES.coerceAtLeast(getInt(KEY_ANIMATION_MINUTES, DEFAULT_ANIMATION_MINUTES))

val SharedPreferences.seekbarVibrate: Boolean get() = getBoolean(KEY_SEEKBAR_VIBRATE, true)

fun SharedPreferences.configuredRadarSources(): List<AnimationSource?> =
    getStringSet(KEY_RADAR_SOURCES, DEFAULT_RADAR_SOURCES)!!.map { str ->
        val parts = str.split(" ")
        Pair(
            parts[0].toInt(),
            parts[1].let { if (it == RADAR_SOURCE_DIVIDER) null else AnimationSource.valueOf(it) })
    }
        .sortedBy { (index, _) -> index }
        .map { (_, radarSource) -> radarSource }
fun SharedPreferences.Editor.setConfiguredRadarSources(animationSources: List<AnimationSource?>): SharedPreferences.Editor =
    putStringSet(KEY_RADAR_SOURCES, animationSources.toStringSet())

private fun List<AnimationSource?>.toStringSet(): Set<String> =
    mapIndexed { i, radarSource -> "$i ${radarSource?.name ?: RADAR_SOURCE_DIVIDER}" }.toSet()

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

val SharedPreferences.newRadarIndicatorConsumedId: Int get() = getInt(KEY_NEW_RADAR_INDICATOR_CONSUMED_ID, 0)
fun SharedPreferences.Editor.setNewRadarIndicatorConsumedId(value: Int): SharedPreferences.Editor =
    putInt(KEY_NEW_RADAR_INDICATOR_CONSUMED_ID, value)

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
