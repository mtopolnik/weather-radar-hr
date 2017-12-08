package com.belotron.weatherradarhr

import android.util.Log

const val LOGTAG = "WeatherRadar"

object MyLog {
    fun d(arg : String) {
        if (enabled(Log.DEBUG)) {
            Log.d(LOGTAG, arg)
        }
    }

    fun d(template: String, arg : Int) {
        if (enabled(Log.DEBUG)) {
            Log.d(LOGTAG, String.format(template, arg))
        }
    }

    fun d(template: String, arg : Any) {
        if (enabled(Log.DEBUG)) {
            Log.d(LOGTAG, String.format(template, arg))
        }
    }

    fun d(template: String, arg0 : Int, arg1 : Int) {
        if (enabled(Log.DEBUG)) {
            Log.d(LOGTAG, String.format(template, arg0, arg1))
        }
    }

    fun i(arg : String) {
        if (enabled(Log.INFO)) {
            Log.i(LOGTAG, arg)
        }
    }

    fun i(template: String, arg : Any) {
        if (enabled(Log.INFO)) {
            Log.i(LOGTAG, String.format(template, arg))
        }
    }

    fun w(message: String) {
        if (enabled(Log.WARN)) {
            Log.w(LOGTAG, message)
        }
    }

    fun e(message: String, exception: Throwable) {
        if (enabled(Log.ERROR)) {
            Log.e(LOGTAG, message, exception)
        }
    }

    fun e(message: String) {
        if (enabled(Log.ERROR)) {
            Log.e(LOGTAG, message)
        }
    }

    private fun enabled(level : Int) = BuildConfig.DEBUG && Log.isLoggable(LOGTAG, level)
}
