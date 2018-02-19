package com.belotron.weatherradarhr

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
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

val threadPool = Executors.newCachedThreadPool().asCoroutineDispatcher()

fun ByteArray.toBitmap() : Bitmap =
        BitmapFactory.decodeByteArray(this, 0, this.size, BitmapFactory.Options())

fun start(block: suspend CoroutineScope.() -> Unit) = launch(
        Looper.myLooper()?.let { Handler(it).asCoroutineDispatcher() } ?: Unconfined,
        start = CoroutineStart.UNDISPATCHED,
        block = block)

fun Context.file(name: String) = File(noBackupFilesDir, name)

fun File.dataIn() = DataInputStream(FileInputStream(this))

fun File.dataOut() = DataOutputStream(FileOutputStream(this))

fun Context.adsEnabled() =
    PreferenceManager.getDefaultSharedPreferences(this).getBoolean(KEY_ADS_ENABLED, true)

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initOcr(applicationContext)
    }
}
