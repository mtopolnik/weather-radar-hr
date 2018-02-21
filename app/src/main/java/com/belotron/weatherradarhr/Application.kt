package com.belotron.weatherradarhr

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.text.format.DateUtils
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.CoroutineStart
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.android.asCoroutineDispatcher
import kotlinx.coroutines.experimental.asCoroutineDispatcher
import kotlinx.coroutines.experimental.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors

const val ADMOB_ID = "ca-app-pub-9052382507824326~6124779019"
const val KEY_ADS_ENABLED = "ads_enabled"

val threadPool = Executors.newCachedThreadPool().asCoroutineDispatcher()

fun start(block: suspend CoroutineScope.() -> Unit) = launch(
        Looper.myLooper()?.let { Handler(it).asCoroutineDispatcher() } ?: Unconfined,
        start = CoroutineStart.UNDISPATCHED,
        block = block)

fun ByteArray.toBitmap(): Bitmap =
        BitmapFactory.decodeByteArray(this, 0, this.size, BitmapFactory.Options())

fun View.setVisible(state: Boolean) {
    visibility = if (state) VISIBLE else GONE
}

val Context.sharedPrefs: SharedPreferences get() = PreferenceManager.getDefaultSharedPreferences(this)

fun Context.file(name: String) = File(noBackupFilesDir, name)

fun Context.ageText(timestamp: Long): CharSequence =
        DateUtils.getRelativeDateTimeString(this, timestamp, DateUtils.MINUTE_IN_MILLIS, DateUtils.DAY_IN_MILLIS, 0)

fun Context.adsEnabled() = sharedPrefs.getBoolean(KEY_ADS_ENABLED, true)

fun SharedPreferences.replaceSetting(keyStr: String, valStr: String, value: Int): Int {
    applyUpdate { putString(keyStr, valStr) }
    return value
}

fun Activity.switchActionBarVisible():Boolean {
    val actionBar = actionBar!!
    if (actionBar.isShowing) {
        actionBar.hide()
    } else {
        actionBar.show()
    }
    return true
}

@SuppressLint("CommitPrefEdits")
inline fun SharedPreferences.commitUpdate(block: SharedPreferences.Editor.() -> Unit) {
    with (edit()) {
        block()
        commit()
    }
}

@SuppressLint("CommitPrefEdits")
inline fun SharedPreferences.applyUpdate(block: SharedPreferences.Editor.() -> Unit) {
    with (edit()) {
        block()
        apply()
    }
}

fun File.dataIn() = DataInputStream(FileInputStream(this))

fun File.dataOut() = DataOutputStream(FileOutputStream(this))

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initOcr(this)
        if (adsEnabled()) {
            MobileAds.initialize(this, ADMOB_ID)
        }
    }
}
