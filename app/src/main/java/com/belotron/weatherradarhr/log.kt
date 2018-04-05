package com.belotron.weatherradarhr

import android.util.Log

const val LOGTAG = "WeatherRadar"

inline fun debug(arg: () -> String) {
    if (BuildConfig.DEBUG && Log.isLoggable(LOGTAG, Log.DEBUG)) {
        Log.d(LOGTAG, arg())
    }
}

inline fun info(arg: () -> String) {
    if (Log.isLoggable(LOGTAG, Log.INFO)) {
        Log.i(LOGTAG, arg())
    }
}

inline fun warn(arg: () -> String) {
    if (Log.isLoggable(LOGTAG, Log.WARN)) {
        Log.w(LOGTAG, arg())
    }
}

fun error(exception: Throwable, arg: () -> String) {
    if (Log.isLoggable(LOGTAG, Log.ERROR)) {
        Log.e(LOGTAG, arg(), exception)
    }
}

fun error(arg: () -> String) {
    if (Log.isLoggable(LOGTAG, Log.ERROR)) {
        Log.e(LOGTAG, arg())
    }
}

