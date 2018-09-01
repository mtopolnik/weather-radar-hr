package com.belotron.weatherradarhr

import android.util.Log

const val LOGTAG = "WeatherRadar"

inline fun debug(lazyMessage: () -> String) {
    if (BuildConfig.DEBUG && Log.isLoggable(LOGTAG, Log.DEBUG)) {
        Log.d(LOGTAG, lazyMessage())
    }
}

inline fun info(lazyMessage: () -> String) {
    if (Log.isLoggable(LOGTAG, Log.INFO)) {
        Log.i(LOGTAG, lazyMessage())
    }
}

inline fun warn(lazyMessage: () -> String) {
    if (Log.isLoggable(LOGTAG, Log.WARN)) {
        Log.w(LOGTAG, lazyMessage())
    }
}

fun severe(exception: Throwable, lazyMessage: () -> String) {
    if (Log.isLoggable(LOGTAG, Log.ERROR)) {
        Log.e(LOGTAG, lazyMessage(), exception)
    }
}

fun severe(lazyMessage: () -> String) {
    if (Log.isLoggable(LOGTAG, Log.ERROR)) {
        Log.e(LOGTAG, lazyMessage())
    }
}

