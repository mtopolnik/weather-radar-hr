package com.belotron.weatherradarhr

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Point
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.support.v4.content.ContextCompat.getColor
import android.text.format.DateUtils.DAY_IN_MILLIS
import android.text.format.DateUtils.MINUTE_IN_MILLIS
import android.text.format.DateUtils.SECOND_IN_MILLIS
import android.text.format.DateUtils.getRelativeDateTimeString
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import com.belotron.weatherradarhr.gifdecode.BitmapFreelists
import com.belotron.weatherradarhr.gifdecode.StandardGifDecoder
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.asCoroutineDispatcher
import kotlinx.coroutines.experimental.launch
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

val threadPool = Executors.newCachedThreadPool { task -> Thread(task, "weather-radar-pool") }.asCoroutineDispatcher()

fun start(block: suspend CoroutineScope.() -> Unit) = launch(UI, start = UNDISPATCHED, block = block)

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        migratePrefs()
        initOcr(this)
        if (adsEnabled) {
            MobileAds.initialize(this, ADMOB_ID)
        }
    }
}

fun View.setVisible(state: Boolean) {
    visibility = if (state) VISIBLE else GONE
}

operator fun Point.component1() = x
operator fun Point.component2() = y
operator fun PointF.component1() = x
operator fun PointF.component2() = y

operator fun RectF.component1() = left
operator fun RectF.component2() = top
operator fun RectF.component3() = right
operator fun RectF.component4() = bottom

fun ImageView?.bitmapSize(p: Point) =
        p.also { this?.drawable
                ?.apply { it.set(intrinsicWidth, intrinsicHeight) }
                ?: it.set(0, 0)
        }.takeIf { it.x > 0 && it.y > 0 }

fun ImageView?.bitmapSize(p: PointF) =
        p.also { this?.drawable
                ?.apply { it.set(intrinsicWidth.toFloat(), intrinsicHeight.toFloat()) }
                ?: it.set(0f, 0f)
        }.takeIf { it.x > 0 && it.y > 0 }

fun TextView.setAgeText(timestamp: Long, isOffline: Boolean) {
    text = context.ageText(timestamp, isOffline)
    val isFresh = isFreshTimestamp(timestamp)
    setTextColor(getColor(context,
            if (isFresh) R.color.text_primary
            else R.color.text_red))
    setShadowLayer(2f, 2f, 2f, getColor(context,
            if (isFresh) R.color.text_shadow
            else R.color.text_red_shadow))
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

fun Context.file(name: String) = File(cacheDir, name)

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

val Bundle.savedStateRecently: Boolean
    get() = System.currentTimeMillis() - getLong(KEY_SAVED_AT) < SECOND_IN_MILLIS

fun File.dataIn() = DataInputStream(FileInputStream(this))

fun File.dataOut() = DataOutputStream(FileOutputStream(this))

fun ByteArray.toBitmap() = StandardGifDecoder(BitmapFreelists()).also { it.read(this) }.decodeFrame(0)

inline fun <T> runOrNull(block: () -> T) = try {
    block()
} catch (t: Throwable) {
    null
}
