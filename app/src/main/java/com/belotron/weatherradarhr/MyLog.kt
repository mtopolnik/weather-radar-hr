package com.belotron.weatherradarhr

import android.util.Log

const val LOGTAG = "WeatherRadar"

object MyLog {
    fun d(arg : String) {
        if (Log.isLoggable(LOGTAG, Log.DEBUG)) {
            Log.d(LOGTAG, arg)
        }
    }

    fun d(template: String, arg : Int) {
        if (Log.isLoggable(LOGTAG, Log.DEBUG)) {
            Log.d(LOGTAG, String.format(template, arg))
        }
    }

    fun d(template: String, arg : Any) {
        if (Log.isLoggable(LOGTAG, Log.DEBUG)) {
            Log.d(LOGTAG, String.format(template, arg))
        }
    }

    fun d(template: String, arg0 : Int, arg1 : Int) {
        if (Log.isLoggable(LOGTAG, Log.DEBUG)) {
            Log.d(LOGTAG, String.format(template, arg0, arg1))
        }
    }

    fun i(arg : String) {
        if (Log.isLoggable(LOGTAG, Log.INFO)) {
            Log.i(LOGTAG, arg)
        }
    }

    fun i(template: String, arg : Any) {
        if (Log.isLoggable(LOGTAG, Log.INFO)) {
            Log.i(LOGTAG, String.format(template, arg))
        }
    }

    fun w(message: String) {
        Log.w(LOGTAG, message)
    }

    fun e(message: String, exception: Throwable) {
        Log.e(LOGTAG, message, exception)
    }

    fun e(message: String) {
        Log.e(LOGTAG, message)
    }
}
