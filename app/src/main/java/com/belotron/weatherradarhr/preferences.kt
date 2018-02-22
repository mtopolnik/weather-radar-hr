package com.belotron.weatherradarhr

import android.content.Context
import android.content.SharedPreferences

private const val KEY_LAST_RELOADED_TIMESTAMP = "last-reloaded-timestamp"
private const val KEY_FREEZE_TIME = "freeze_time"
private const val KEY_FRAME_DELAY = "frame_delay"

private const val DEFAULT_FRAME_DELAY_FACTOR = 12
private const val DEFAULT_FREEZE_TIME = 1500

val SharedPreferences.frameDelayFactor: Int get() = getInt(KEY_FRAME_DELAY, DEFAULT_FRAME_DELAY_FACTOR)

val SharedPreferences.freezeTime: Int get() = getInt(KEY_FREEZE_TIME, DEFAULT_FREEZE_TIME)

val SharedPreferences.animationDuration: Int get() = ANIMATION_COVERS_MINUTES * frameDelayFactor + freezeTime

var SharedPreferences.lastReloadedTimestamp: Long
    get() = getLong(KEY_LAST_RELOADED_TIMESTAMP, 0L)
    set(value) = applyUpdate { putLong(KEY_LAST_RELOADED_TIMESTAMP, value) }


fun Context.migratePrefs() {
    MyLog.i{"Migrating prefs"}
    with(sharedPrefs) {
        commitUpdate {
            putInt(KEY_FRAME_DELAY, frameDelayFactorFromString)
            putInt(KEY_FREEZE_TIME, freezeTimeFromString)
        }
    }
}

private const val DEFAULT_STR_FRAME_DELAY = "frameDelay0"
private const val DEFAULT_STR_FREEZE_TIME = "freeze0"

private val SharedPreferences.frameDelayFactorFromString: Int get() =
    getString(KEY_FRAME_DELAY, DEFAULT_STR_FRAME_DELAY).let { delayStr ->
        when (delayStr) {
            DEFAULT_STR_FRAME_DELAY -> return DEFAULT_FRAME_DELAY_FACTOR // 85 min/sec
            "frameDelay1" -> return 26 // 40 min/sec
            "frameDelay2" -> return 47 // 20 min/sec
            else -> replaceSetting(KEY_FRAME_DELAY, DEFAULT_STR_FRAME_DELAY, DEFAULT_FRAME_DELAY_FACTOR)
        }
    }

private val SharedPreferences.freezeTimeFromString: Int get() =
    getString(KEY_FREEZE_TIME, DEFAULT_STR_FREEZE_TIME).let { freezeStr ->
        when (freezeStr) {
            DEFAULT_STR_FREEZE_TIME -> return DEFAULT_FREEZE_TIME
            "freeze1" -> return 2500
            "freeze2" -> return 3500
            else -> replaceSetting(KEY_FREEZE_TIME, DEFAULT_STR_FREEZE_TIME, DEFAULT_FREEZE_TIME)
        }
    }
