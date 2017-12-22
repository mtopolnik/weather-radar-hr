package com.belotron.weatherradarhr

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Bitmap.createBitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.PersistableBundle
import android.text.format.DateUtils.DAY_IN_MILLIS
import android.text.format.DateUtils.HOUR_IN_MILLIS
import android.text.format.DateUtils.MINUTE_IN_MILLIS
import android.text.format.DateUtils.formatElapsedTime
import android.text.format.DateUtils.getRelativeDateTimeString
import android.widget.RemoteViews
import com.belotron.weatherradarhr.KradarOcr.ocrKradarTimestamp
import com.belotron.weatherradarhr.LradarOcr.ocrLradarTimestamp
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

private const val SECS_IN_HOUR = 3600L
private const val SECS_IN_MINUTE = 60L
private const val RETRY_PERIOD_MINUTES = 10L
private const val REFRESH_IMAGE_JOB_ID = 700713272
private const val UPDATE_AGE_JOB_ID = 700723272
private const val LRADAR_CROP_Y_TOP = 40
private const val KRADAR_CROP_X_RIGHT = 480
private const val EXTRA_WIDGET_DESC_INDEX = "widgetDescIndex"

private val widgetDescriptors = arrayOf(
        WidgetDescriptor("http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar.gif", 10,
                LradarWidgetProvider::class.java,
                R.drawable.lradar_widget_preview,
                { val bitmap = it.toBitmap()
                    TimestampedBitmap(
                            ocrLradarTimestamp(bitmap),
                            createBitmap(bitmap, 0, LRADAR_CROP_Y_TOP, bitmap.width, bitmap.height - LRADAR_CROP_Y_TOP)
            )}),
        WidgetDescriptor("http://vrijeme.hr/kradar.gif", 15,
                KradarWidgetProvider::class.java,
                R.drawable.kradar_widget_preview,
                { val bitmap = it.toBitmap()
                    TimestampedBitmap(
                            ocrKradarTimestamp(bitmap),
                            createBitmap(bitmap, 0, 0, KRADAR_CROP_X_RIGHT, bitmap.height)) })
)

private data class WidgetDescriptor(
        val url: String,
        val updatePeriodMinutes: Long,
        private val providerClass: Class<out AppWidgetProvider>,
        val previewResourceId: Int,
        val timestampedBitmapFrom: (ByteArray) -> TimestampedBitmap
) {
    fun imgFilename() = url.substringAfterLast('/')
    fun timestampFilename() = imgFilename() + ".timestamp"
    fun providerName(context: Context) = ComponentName(context, providerClass)
    fun index() = widgetDescriptors.indexOf(this)
    fun toExtras() : PersistableBundle {
        val b = PersistableBundle()
        b.putInt(EXTRA_WIDGET_DESC_INDEX, index())
        return b
    }
}

private data class TimestampedBitmap(val timestamp: Long, val bitmap: Bitmap)

fun updateWidgets(context : Context) {
    val appContext = context.applicationContext
    widgetDescriptors.forEach { wDesc ->
        if (!AppWidgetManager.getInstance(context).getAppWidgetIds(wDesc.providerName(context)).isEmpty()) {
            launch(Unconfined) launch@ {
                fetchImageAndUpdateWidget(appContext, wDesc, onlyIfNew = false)
            }
        }
    }
}

class LradarWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        onUpdateWidget(context, widgetDescriptors[0], "Lradar")
    }
}

class KradarWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        onUpdateWidget(context, widgetDescriptors[1], "Kradar")
    }
}

private fun onUpdateWidget(context : Context, wDesc : WidgetDescriptor, name : String) {
    MyLog.w("onUpdate $name")
    launch(Unconfined) {
        val lastModified = fetchImageAndUpdateWidget(context.applicationContext, wDesc, onlyIfNew = false)
        scheduleWidgetUpdate(context, wDesc,
                if (lastModified != null) millisToNextUpdate(lastModified, wDesc.updatePeriodMinutes)
                else JobInfo.DEFAULT_INITIAL_BACKOFF_MILLIS
        )
    }
    scheduleUpdateAge(context.applicationContext, wDesc)
}

class RefreshImageService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        MyLog.i("RefreshImageService start job")
        val wDescIndex = params.extras[EXTRA_WIDGET_DESC_INDEX] as Int
        val wDesc = widgetDescriptors[wDescIndex]
        launch(Unconfined) {
            val lastModified = fetchImageAndUpdateWidget(applicationContext, wDesc, onlyIfNew = true)
            jobFinished(params, lastModified == null)
            if (lastModified != null) {
                scheduleWidgetUpdate(applicationContext, wDesc,
                        millisToNextUpdate(lastModified, wDesc.updatePeriodMinutes))
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        MyLog.i("RefreshImageService stop job")
        return true
    }
}

class UpdateAgeService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        MyLog.i("UpdateAgeService start job")
        val wDescIndex = params.extras[EXTRA_WIDGET_DESC_INDEX] as Int
        val wDesc = widgetDescriptors[wDescIndex]
        updateRemoteViews(applicationContext, wDesc, readImgAndTimestamp(applicationContext, wDesc))
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean {
        MyLog.i("UpdateAgeService stop job")
        return true
    }
}

private suspend fun fetchImageAndUpdateWidget(
        context: Context, wDesc: WidgetDescriptor, onlyIfNew: Boolean
): Long? {
    try {
        val (lastModified, imgBytes) = fetchImage(context, wDesc.url, onlyIfNew)
        if (imgBytes == null) {

            return null
        }
        val tsBitmap = wDesc.timestampedBitmapFrom(imgBytes)
        writeImgAndTimestamp(context, wDesc, tsBitmap)
        updateRemoteViews(context, wDesc, tsBitmap)
        return lastModified
    } catch (e: ImageFetchException) {
        if (e.cached != null) {
            updateRemoteViews(context, wDesc, wDesc.timestampedBitmapFrom(e.cached))
        } else if (!onlyIfNew) {
            MyLog.w("Failed to fetch ${wDesc.imgFilename()}, using preview")
            updateRemoteViews(context, wDesc, TimestampedBitmap(
                    0L,
                    (context.resources.getDrawable(wDesc.previewResourceId, null) as BitmapDrawable).bitmap))
        }
        return null
    } catch (t: Throwable) {
        MyLog.e("Widget refresh failure", t)
        return null
    }
}

private fun updateRemoteViews(context: Context, wDesc: WidgetDescriptor, tsBitmap: TimestampedBitmap?) {
    val remoteViews = RemoteViews(context.packageName, R.layout.app_widget)
    remoteViews.setOnClickPendingIntent(R.id.img_view_widget, onClickIntent(context))
    if (tsBitmap != null) {
        remoteViews.setImageViewBitmap(R.id.img_view_widget, tsBitmap.bitmap)
        remoteViews.setTextViewText(R.id.text_view_widget,
                getRelativeDateTimeString(context, tsBitmap.timestamp, MINUTE_IN_MILLIS, DAY_IN_MILLIS, 0))
    } else {
        remoteViews.setTextViewText(R.id.text_view_widget, "Radar image unavailable. Tap to retry.")
    }
    AppWidgetManager.getInstance(context).updateAppWidget(wDesc.providerName(context), remoteViews)
    MyLog.i("Updated Remote Views")
}

private fun scheduleWidgetUpdate(context: Context, wDesc : WidgetDescriptor, latencyMillis: Long) {
    val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    val resultCode = jobScheduler.schedule(
            JobInfo.Builder(REFRESH_IMAGE_JOB_ID + wDesc.index(),
                    ComponentName(context, RefreshImageService::class.java))
                    .setExtras(wDesc.toExtras())
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(latencyMillis)
                    .setOverrideDeadline(HOUR_IN_MILLIS)
                    .build())
    val latencyStr = formatElapsedTime(MILLISECONDS.toSeconds(latencyMillis))
    reportScheduleResult("refresh radar image after $latencyStr", resultCode)
}

private fun scheduleUpdateAge(context: Context, wDesc: WidgetDescriptor) {
    val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    val resultCode = jobScheduler.schedule(
            JobInfo.Builder(UPDATE_AGE_JOB_ID + wDesc.index(), ComponentName(context, UpdateAgeService::class.java))
                    .setExtras(wDesc.toExtras())
                    .setPeriodic(MINUTE_IN_MILLIS)
                    .build())
    reportScheduleResult("update age every minute", resultCode)
}

private fun reportScheduleResult(task: String, resultCode: Int) {
    when (resultCode) {
        JobScheduler.RESULT_SUCCESS -> MyLog.i("Scheduled to $task")
        JobScheduler.RESULT_FAILURE -> MyLog.e("Failed to schedule to $task")
        else -> throw AssertionError("Unknown scheduler result code $resultCode")
    }
}

private fun onClickIntent(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
    intent.addCategory("android.intent.category.LAUNCHER")
    intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
    intent.component = ComponentName(context.packageName, MainActivity::class.java.name)
    return PendingIntent.getActivity(context, 0, intent, 0)
}

private fun writeImgAndTimestamp(context: Context, wDesc: WidgetDescriptor, tsBitmap: TimestampedBitmap) {
    val fname = wDesc.timestampFilename()
    val growingFile = file(context, fname + ".growing")
    dataOut(fileOut(growingFile)).use {
        it.writeLong(tsBitmap.timestamp)
        tsBitmap.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    if (!growingFile.renameTo(file(context, fname))) {
        throw IOException("Couldn't rename $growingFile")
    }
}

private fun readImgAndTimestamp(context : Context, wDesc: WidgetDescriptor) : TimestampedBitmap? {
    val file = file(context, wDesc.timestampFilename())
    return try {
        DataInputStream(fileIn(file)).use { TimestampedBitmap(it.readLong(), BitmapFactory.decodeStream(it)) }
    } catch (e : Exception) {
        null
    }
}

private fun file(context : Context, name : String) = File(context.noBackupFilesDir, name)

fun dataIn(input: InputStream) = DataInputStream(input)

fun dataOut(output: OutputStream) = DataOutputStream(output)

fun fileOut(f : File) = FileOutputStream(f)

fun fileIn(f : File) = FileInputStream(f)

/**
 * We use Last-Modified modulo one hour due to DHMZ's broken Last-Modified
 * reporting (it's off by one hour)
 *
 * @param lastModified last modified time in seconds past full hour
 */
private fun millisToNextUpdate(lastModified : Long, updateIntervalMinutes: Long) : Long {
    require(updateIntervalMinutes in 0 until 60, { "updateInterval out of range: $updateIntervalMinutes" })
    require(lastModified in 0 until SECS_IN_HOUR, { "lastModified out of range: $lastModified" })

    val now = hourRelativeCurrentTime()
    val modifiedSecondsAgo = if (now >= lastModified) now - lastModified
                             else (now + SECS_IN_HOUR) - lastModified
    val proposedDelay = updateIntervalMinutes * SECS_IN_MINUTE - modifiedSecondsAgo
    return SECONDS.toMillis(if (proposedDelay > 0) proposedDelay
                            else RETRY_PERIOD_MINUTES * SECS_IN_MINUTE)
}

private fun hourRelativeCurrentTime() : Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("GMT"))
    return SECS_IN_MINUTE * cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND)
}
