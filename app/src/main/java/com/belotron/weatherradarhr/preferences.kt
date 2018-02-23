package com.belotron.weatherradarhr

import android.content.Context
import android.content.SharedPreferences

private const val KEY_LAST_RELOADED_TIMESTAMP = "last-reloaded-timestamp"
private const val KEY_FREEZE_TIME = "freeze_time_millis"
private const val KEY_ANIMATION_RATE = "animation_rate_mins_per_sec"

const val DEFAULT_ANIMATION_RATE = 83
private const val DEFAULT_FREEZE_TIME = 1500

val SharedPreferences.rateMinsPerSec: Int get() = getInt(KEY_ANIMATION_RATE, DEFAULT_ANIMATION_RATE)

val SharedPreferences.freezeTimeMillis: Int get() = getInt(KEY_FREEZE_TIME, DEFAULT_FREEZE_TIME)

val SharedPreferences.animationDurationMillis: Int get() =
    1000 * ANIMATION_COVERS_MINUTES / rateMinsPerSec + freezeTimeMillis

var SharedPreferences.lastReloadedTimestamp: Long
    get() = getLong(KEY_LAST_RELOADED_TIMESTAMP, 0L)
    set(value) = applyUpdate { putLong(KEY_LAST_RELOADED_TIMESTAMP, value) }


fun Context.migratePrefs() {
    with(sharedPrefs) {
        commitUpdate {
            animationRateFromOldSetting?.also {
                putInt(KEY_ANIMATION_RATE, it)
                remove(KEY_FRAME_DELAY_OLD)
            }
            freezeTimeFromOldSetting?.also {
                putInt(KEY_FREEZE_TIME, it)
                remove(KEY_FREEZE_TIME_OLD)
            }
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
            "freeze0" -> DEFAULT_ANIMATION_RATE
            "freeze1" -> return 2500
            "freeze2" -> return 3500
            else -> null
        }
    }
