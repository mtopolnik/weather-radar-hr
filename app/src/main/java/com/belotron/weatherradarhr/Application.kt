package com.belotron.weatherradarhr

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.support.v4.content.ContextCompat.getColor
import android.text.format.DateUtils
import android.text.format.DateUtils.DAY_IN_MILLIS
import android.text.format.DateUtils.MINUTE_IN_MILLIS
import android.text.format.DateUtils.getRelativeDateTimeString
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.RemoteViews
import android.widget.TextView
import com.belotron.weatherradarhr.gifdecode.BitmapFreelists
import com.belotron.weatherradarhr.gifdecode.StandardGifDecoder
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.experimental.asCoroutineDispatcher
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.HOURS

const val ADMOB_ID = "ca-app-pub-9052382507824326~6124779019"
const val KEY_ADS_ENABLED = "ads_enabled"
private const val KEY_SAVED_AT = "instance-state-saved-at"

val threadPool = Executors.newCachedThreadPool().asCoroutineDispatcher()

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        migratePrefs()
        initOcr(this)
        if (sharedPrefs.adsEnabled) {
            MobileAds.initialize(this, ADMOB_ID)
        }
    }
}

fun View.setVisible(state: Boolean) {
    visibility = if (state) VISIBLE else GONE
}

fun TextView.setAgeText(timestamp: Long, isOffline: Boolean) {
    text = context.ageText(timestamp, isOffline)
    val isFresh = isFreshTimestamp(timestamp)
    setTextColor(getColor(context,
            if (isFresh) R.color.textPrimary
            else R.color.textRed))
    setShadowLayer(2f, 2f, 2f, getColor(context,
            if (isFresh) R.color.textShadow
            else R.color.textRedShadow))
}

fun RemoteViews.setAgeText(context: Context, timestamp: Long, isOffline: Boolean) {
    val ageText = context.ageText(timestamp, isOffline)
    if (isFreshTimestamp(timestamp)) {
        setBlackText(ageText)
    } else {
        setRedText(ageText)
    }
}

fun RemoteViews.setBlackText(text: CharSequence) = setWidgetText(text, R.id.text_black, R.id.text_red)

fun RemoteViews.setRedText(text: CharSequence) = setWidgetText(text, R.id.text_red, R.id.text_black)

fun RemoteViews.setWidgetText(text: CharSequence, visibleViewId: Int, invisibleViewId: Int) {
    setViewVisibility(visibleViewId, VISIBLE)
    setViewVisibility(invisibleViewId, GONE)
    setTextViewText(visibleViewId, text)
    setTextViewText(invisibleViewId, "")
}

private fun isFreshTimestamp(timestamp: Long) = timestamp > System.currentTimeMillis() - HOURS.toMillis(1)

fun Context.file(name: String) = File(noBackupFilesDir, name)

fun Context.ageText(timestamp: Long, isOffline: Boolean): CharSequence = (if (isOffline) "Offline - " else "") +
        getRelativeDateTimeString(this, timestamp, MINUTE_IN_MILLIS, DAY_IN_MILLIS, 0)


fun Activity.switchActionBarVisible():Boolean {
    val actionBar = actionBar!!
    if (actionBar.isShowing) {
        actionBar.hide()
    } else {
        actionBar.show()
    }
    return true
}

fun Bundle.recordSavingTime() = putLong(KEY_SAVED_AT, System.currentTimeMillis())

val Bundle.wasFastResume: Boolean
    get() = System.currentTimeMillis() - getLong(KEY_SAVED_AT) < DateUtils.SECOND_IN_MILLIS

fun File.dataIn() = DataInputStream(FileInputStream(this))

fun File.dataOut() = DataOutputStream(FileOutputStream(this))

fun ByteArray.toBitmap(): Bitmap {
    return StandardGifDecoder(BitmapFreelists()).also { it.read(this) }.decodeFrame(0)
//    return BitmapFactory.decodeByteArray(this, 0, this.size, BitmapFactory.Options())
}
