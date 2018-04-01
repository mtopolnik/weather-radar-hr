package com.belotron.weatherradarhr

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import java.lang.Math.max

private const val KEY_LAST_RELOADED_TIMESTAMP = "last-reloaded-timestamp"
private const val KEY_LAST_PAUSED_TIMESTAMP = "last-paused-timestamp"
private const val KEY_FREEZE_TIME = "freeze_time_millis"
private const val KEY_ANIMATION_RATE = "animation_rate_mins_per_sec"

const val DEFAULT_ANIMATION_RATE = 85
const val DEFAULT_FREEZE_TIME = 1500

val Context.sharedPrefs: SharedPreferences get() = PreferenceManager.getDefaultSharedPreferences(this)

val SharedPreferences.adsEnabled: Boolean get() = getBoolean(KEY_ADS_ENABLED, true)

val SharedPreferences.rateMinsPerSec: Int get() = max(1, getInt(KEY_ANIMATION_RATE, DEFAULT_ANIMATION_RATE))

val SharedPreferences.freezeTimeMillis: Int get() = getInt(KEY_FREEZE_TIME, DEFAULT_FREEZE_TIME)

val SharedPreferences.lastReloadedTimestamp: Long get() = getLong(KEY_LAST_RELOADED_TIMESTAMP, 0L)
fun SharedPreferences.Editor.setLastReloadedTimestamp(value: Long) = putLong(KEY_LAST_RELOADED_TIMESTAMP, value)

val SharedPreferences.lastPausedTimestamp: Long get() = getLong(KEY_LAST_PAUSED_TIMESTAMP, 0L)
fun SharedPreferences.Editor.setLastPausedTimestamp(value: Long) = putLong(KEY_LAST_PAUSED_TIMESTAMP, value)

fun Context.migratePrefs() {
    with(sharedPrefs) {
        commitUpdate {
            animationRateFromOldSetting?.also {
                info { "Migrating animation rate" }
                putInt(KEY_ANIMATION_RATE, it)
                remove(KEY_FRAME_DELAY_OLD)
            }
            freezeTimeFromOldSetting?.also {
                info { "Migrating freeze time" }
                putInt(KEY_FREEZE_TIME, it)
                remove(KEY_FREEZE_TIME_OLD)
            }
        }
    }
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

private const val KEY_FRAME_DELAY_OLD = "frame_delay"
private const val KEY_FREEZE_TIME_OLD = "freeze_time"

private val SharedPreferences.animationRateFromOldSetting: Int? get() =
    getString(KEY_FRAME_DELAY_OLD, null).let { delayStr ->
        when (delayStr) {
            "frameDelay0" -> DEFAULT_ANIMATION_RATE
            "frameDelay1" -> return 38
            "frameDelay2" -> return 21
            else -> null
        }
    }

private val SharedPreferences.freezeTimeFromOldSetting: Int? get() =
    getString(KEY_FREEZE_TIME_OLD, null).let { freezeStr ->
        when (freezeStr) {
            "freeze0" -> DEFAULT_FREEZE_TIME
            "freeze1" -> return 2500
            "freeze2" -> return 3500
            else -> null
        }
    }
