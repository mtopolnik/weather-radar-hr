package com.belotron.weatherradarhr

import android.util.Log

const val LOGTAG = "WeatherRadar"

inline fun debug(arg: () -> String) {
    if (logLevelEnabled(Log.DEBUG)) {
        Log.d(LOGTAG, arg())
    }
}

inline fun info(arg: () -> String) {
    if (logLevelEnabled(Log.INFO)) {
        Log.i(LOGTAG, arg())
    }
}

inline fun warn(arg: () -> String) {
    if (logLevelEnabled(Log.WARN)) {
        Log.w(LOGTAG, arg())
    }
}

fun error(exception: Throwable, arg: () -> String) {
    if (logLevelEnabled(Log.ERROR)) {
        Log.e(LOGTAG, arg(), exception)
    }
}

fun error(arg: () -> String) {
    if (logLevelEnabled(Log.ERROR)) {
        Log.e(LOGTAG, arg())
    }
}

fun logLevelEnabled(level : Int) = BuildConfig.DEBUG && Log.isLoggable(LOGTAG, level)
