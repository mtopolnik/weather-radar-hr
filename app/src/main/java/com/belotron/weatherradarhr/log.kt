package com.belotron.weatherradarhr

import android.util.Log
import com.belotron.weatherradarhr.CcOption.CC_PRIVATE
import com.belotron.weatherradarhr.CcOption.NO_CC
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

var privateLogEnabled = false

const val LOGTAG = "WeatherRadar"

private val logFile get() = File(appContext.noBackupFilesDir, "app-widget-log.txt")

enum class CcOption { CC_PRIVATE, NO_CC }

inline fun debug(lazyMessage: () -> String) {
    if (BuildConfig.DEBUG && Log.isLoggable(LOGTAG, Log.DEBUG)) {
        Log.d(LOGTAG, lazyMessage())
    }
}

inline fun info(ccOption: CcOption, lazyMessage: () -> String) {
    lazyMessage().also { msg ->
        if (Log.isLoggable(LOGTAG, Log.INFO)) {
            Log.i(LOGTAG, msg)
        }
        logPrivate(ccOption, msg)
    }
}

inline fun info(lazyMessage: () -> String) {
    info(NO_CC, lazyMessage)
}

inline fun warn(ccOption: CcOption, lazyMessage: () -> String) {
    lazyMessage().also { msg ->
        if (Log.isLoggable(LOGTAG, Log.WARN)) {
            Log.w(LOGTAG, msg)
        }
        logPrivate(ccOption, msg)
    }
}

inline fun warn(lazyMessage: () -> String) {
    warn(NO_CC, lazyMessage)
}

inline fun severe(ccOption: CcOption, exception: Throwable, lazyMessage: () -> String) {
    lazyMessage().also { msg ->
        if (Log.isLoggable(LOGTAG, Log.ERROR)) {
            Log.e(LOGTAG, msg, exception)
        }
        logPrivate(ccOption, msg, exception)
    }
}

inline fun severe(exception: Throwable, lazyMessage: () -> String) {
    severe(NO_CC, exception, lazyMessage)
}

inline fun severe(ccOption: CcOption, lazyMessage: () -> String) {
    lazyMessage().also { msg ->
        if (Log.isLoggable(LOGTAG, Log.ERROR)) {
            Log.e(LOGTAG, msg)
        }
        logPrivate(ccOption, msg)
    }
}

inline fun severe(lazyMessage: () -> String) {
    severe(NO_CC, lazyMessage)
}

fun logPrivate(ccOption: CcOption, msg: String, exception: Throwable? = null) {
    if (ccOption != CC_PRIVATE || !privateLogEnabled) {
        return
    }
    val now = System.currentTimeMillis()
    val stacktrace = exception?.let { e ->
        ": " + StringWriter().also { sw -> PrintWriter(sw).use { e.printStackTrace(it) } }.toString()
    } ?: ""
    logFile.writer().use {
        it.println("${appContext.dateFormat.format(now)} ${appContext.timeFormat.format(now)} $msg$stacktrace")
    }
}

fun viewPrivateLog() = logFile.takeIf { it.exists() }?.readText() ?: "The log is empty."

fun clearPrivateLog() {
    val now = System.currentTimeMillis()
    val ctx = appContext
    logFile.writer(false).use {
        it.println("${ctx.dateFormat.format(now)} ${ctx.timeFormat.format(now)} Log cleared")
    }
}
