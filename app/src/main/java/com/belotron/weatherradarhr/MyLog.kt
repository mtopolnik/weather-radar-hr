package com.belotron.weatherradarhr

import android.util.Log

const val LOGTAG = "WeatherRadar"

object MyLog {
    inline fun d(arg: () -> String) {
        if (logLevelEnabled(Log.DEBUG)) {
            Log.d(LOGTAG, arg())
        }
    }

    @JvmStatic
    fun ii(arg: String) {
        if (logLevelEnabled(Log.INFO)) {
            Log.i(LOGTAG, arg)
        }
    }

    inline fun i(arg: () -> String) {
        if (logLevelEnabled(Log.INFO)) {
            Log.i(LOGTAG, arg())
        }
    }

    fun w(message: String) {
        if (logLevelEnabled(Log.WARN)) {
            Log.w(LOGTAG, message)
        }
    }

    fun e(message: String, exception: Throwable) {
        if (logLevelEnabled(Log.ERROR)) {
            Log.e(LOGTAG, message, exception)
        }
    }

    fun e(message: String) {
        if (logLevelEnabled(Log.ERROR)) {
            Log.e(LOGTAG, message)
        }
    }

}

fun logLevelEnabled(level : Int) = BuildConfig.DEBUG && Log.isLoggable(LOGTAG, level)
