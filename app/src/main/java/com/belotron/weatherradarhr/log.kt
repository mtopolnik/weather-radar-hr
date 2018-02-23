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

fun warn(message: String) {
    if (logLevelEnabled(Log.WARN)) {
        Log.w(LOGTAG, message)
    }
}

fun error(message: String, exception: Throwable) {
    if (logLevelEnabled(Log.ERROR)) {
        Log.e(LOGTAG, message, exception)
    }
}

fun error(message: String) {
    if (logLevelEnabled(Log.ERROR)) {
        Log.e(LOGTAG, message)
    }
}

fun logLevelEnabled(level : Int) = BuildConfig.DEBUG && Log.isLoggable(LOGTAG, level)
