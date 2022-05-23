package com.belotron.weatherradarhr

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils.*
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat.getColor
import com.belotron.weatherradarhr.gifdecode.BitmapPixels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.PrintWriter
import java.text.DateFormat
import java.util.concurrent.TimeUnit.HOURS
import android.text.format.DateFormat as AndroidDateFormat

private const val KEY_SAVED_AT = "instance-state-saved-at"

lateinit var appContext: Context
lateinit var appCoroScope: CoroutineScope

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = this
        val masterJob = Job()
        appCoroScope = object : CoroutineScope {
            override val coroutineContext get() = Dispatchers.Main.immediate + masterJob
        }
        privateLogEnabled = mainPrefs.widgetLogEnabled
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

fun TextView.setAgeText(
        timestamp: Long, isOffline: Boolean,
        dateFormat: DateFormat, timeFormat: DateFormat
) {
    val now = System.currentTimeMillis()
    text = ageText(
            timestamp = timestamp, now = now, isOffline = isOffline,
            dateFormat = dateFormat, timeFormat = timeFormat)
    val isFresh = isFreshTimestamp(timestamp = timestamp, now = now)
    setTextColor(getColor(context,
            if (isFresh) R.color.text_primary
            else R.color.text_red))
    setShadowLayer(2f, 2f, 2f, getColor(context,
            if (isFresh) R.color.text_shadow
            else R.color.text_red_shadow))
}

fun RemoteViews.setAgeText(context: Context, timestamp: Long, isOffline: Boolean) {
    val now = System.currentTimeMillis()
    val ageText = ageText(
            timestamp = timestamp, now = now, isOffline = isOffline,
            dateFormat = context.dateFormat, timeFormat = context.timeFormat)
    if (isFreshTimestamp(timestamp, now)) {
        setBlackText(ageText)
    } else {
        setRedText(ageText)
    }
}

private fun ageText(
        timestamp: Long, now: Long, isOffline: Boolean,
        dateFormat: DateFormat, timeFormat: DateFormat
): CharSequence {
    val format = if (timestamp > now - DAY_IN_MILLIS) timeFormat else dateFormat
    return (if (isOffline) "Offline - " else "") +
            getRelativeTimeSpanString(timestamp, now, MINUTE_IN_MILLIS, 0) +
            ", ${format.format(timestamp)}"
}

private fun isFreshTimestamp(timestamp: Long, now: Long) = timestamp > now - HOURS.toMillis(1)

fun RemoteViews.setBlackText(text: CharSequence) = setWidgetText(text, R.id.text_black, R.id.text_red)

fun RemoteViews.setRedText(text: CharSequence) = setWidgetText(text, R.id.text_red, R.id.text_black)

fun RemoteViews.setWidgetText(text: CharSequence, visibleViewId: Int, invisibleViewId: Int) {
    setViewVisibility(visibleViewId, VISIBLE)
    setViewVisibility(invisibleViewId, GONE)
    setTextViewText(visibleViewId, text)
    setTextViewText(invisibleViewId, "")
}

fun Context.fileInCache(name: String) = File(cacheDir, name)

val Context.dateFormat get() = AndroidDateFormat.getDateFormat(this)!!
val Context.timeFormat get() = AndroidDateFormat.getTimeFormat(this)!!

fun Bundle.recordSavingTime() = putLong(KEY_SAVED_AT, System.currentTimeMillis())

val Bundle.savedStateRecently: Boolean
    get() = System.currentTimeMillis() - getLong(KEY_SAVED_AT) < SECOND_IN_MILLIS

fun File.dataIn() = DataInputStream(FileInputStream(this))

fun File.dataOut() = DataOutputStream(FileOutputStream(this))

fun File.writer(append: Boolean = true) = PrintWriter(FileWriter(this, append))

fun ByteArray.decodeToBitmap() = BitmapFactory.decodeByteArray(this, 0, size)!!

fun Bitmap.asPixels() = BitmapPixels(this)

fun Resources.getColorVnr(id: Int) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) getColor(id, null)
    else getColor(id)

inline fun <T> runOrNull(block: () -> T) = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

fun Rect.reset(): Rect {
    set(0, 0, 0, 0)
    return this
}

fun View.isDescendantOf(that: View): Boolean {
    if (this === that) {
        return true
    }
    var currParent: View? = parent as? View
    while (currParent != null) {
        if (currParent === that) {
            return true
        }
        currParent = currParent.parent as? View
    }
    return false
}
