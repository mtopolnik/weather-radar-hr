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
import android.os.PersistableBundle
import android.text.format.DateUtils.DAY_IN_MILLIS
import android.text.format.DateUtils.HOUR_IN_MILLIS
import android.text.format.DateUtils.MINUTE_IN_MILLIS
import android.text.format.DateUtils.formatElapsedTime
import android.text.format.DateUtils.getRelativeDateTimeString
import android.widget.RemoteViews
import com.belotron.weatherradarhr.ImageRequest.sendImageRequest
import com.belotron.weatherradarhr.KradarOcr.ocrKradarTimestamp
import com.belotron.weatherradarhr.LradarOcr.ocrLradarTimestamp
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

private const val SECS_IN_HOUR = 3600L
private const val SECS_IN_MINUTE = 60L
private const val RETRY_PERIOD_MINUTES = 10L
private const val REFRESH_IMAGE_JOB_ID = 700713272
private const val UPDATE_AGE_JOB_ID = 700713273
private const val LRADAR_CROP_Y_TOP = 40
private const val KRADAR_CROP_X_RIGHT = 480
private const val EXTRA_WIDGET_DESC_INDEX = "widgetDescIndex"

private val widgetDescriptors = arrayOf(
        WidgetDescriptor("http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar.gif", 10,
                LradarWidgetProvider::class.java,
                { val bitmap = it.toBitmap()
                    TimestampedBitmap(
                            createBitmap(bitmap, 0, LRADAR_CROP_Y_TOP, bitmap.width, bitmap.height - LRADAR_CROP_Y_TOP),
                            ocrLradarTimestamp(bitmap)
            )}),
        WidgetDescriptor("http://vrijeme.hr/kradar.gif", 15,
                KradarWidgetProvider::class.java,
                { val bitmap = it.toBitmap()
                    TimestampedBitmap(
                            createBitmap(bitmap, 0, 0, KRADAR_CROP_X_RIGHT, bitmap.height),
                            ocrKradarTimestamp(bitmap)) })
)

private data class WidgetDescriptor(
        val url : String,
        val updatePeriodMinutes : Long,
        val providerClass : Class<out AppWidgetProvider>,
        val timestampedBitmapFrom: (ByteArray) -> TimestampedBitmap
) {

    fun imgFilename() = url.substringAfterLast('/')
    fun timestampFilename() = imgFilename() + ".timestamp"
    fun toExtras() : PersistableBundle {
        val b = PersistableBundle()
        b.putInt(EXTRA_WIDGET_DESC_INDEX, widgetDescriptors.indexOf(this))
        return b
    }
}

private data class TimestampedBitmap(val bitmap : Bitmap?, val timestamp : Long?) {
    fun isEmpty() = bitmap == null && timestamp == null
}

fun updateWidgets(context : Context) {
    widgetDescriptors.forEach {
        updateWidgetAndScheduleNext(context.applicationContext, it, forceLoadImage = true)
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
    MyLog.w("""onUpdate $name""")
    updateWidgetAndScheduleNext(context.applicationContext, wDesc, forceLoadImage = true)
    scheduleUpdateAge(context.applicationContext, wDesc)
}

class RefreshImageService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        MyLog.i("RefreshImageService start job")
        val wDescIndex = params.extras[EXTRA_WIDGET_DESC_INDEX] as Int
        updateWidgetAndScheduleNext(applicationContext, widgetDescriptors[wDescIndex],
                onImageUpdated = { jobFinished(params, false) },
                onImageNotUpdated = { jobFinished(params, true) })
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
        val imgTimestamp = readImgTimestamp(applicationContext, wDesc)
        updateRemoteViews(applicationContext, wDesc, TimestampedBitmap(null, imgTimestamp))
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean {
        MyLog.i("UpdateAgeService stop job")
        return true
    }

    private fun readImgTimestamp(context : Context, wDesc: WidgetDescriptor) : Long? {
        val file = file(context, wDesc.timestampFilename())
        return if (file.exists())
            DataInputStream(fileIn(file)).use { it.readLong() }
        else null
    }
}

private fun updateWidgetAndScheduleNext(context: Context,
                                        wDesc: WidgetDescriptor,
                                        forceLoadImage: Boolean = false,
                                        onImageUpdated: () -> Unit = {},
                                        onImageNotUpdated: () -> Unit = {}
) {
    sendImageRequest(context, wDesc.url,
            useIfModifiedSince = !forceLoadImage,
            //                      seconds past full hour
            onSuccess = { imgBytes, lastModified ->
                try {
                    val tsBitmap = wDesc.timestampedBitmapFrom(imgBytes)
                    writeImgTimestamp(context, wDesc, tsBitmap)
                    fileOut(file(context, wDesc.imgFilename())).use { it.write(imgBytes) }
                    updateRemoteViews(context, wDesc, tsBitmap)
                    onImageUpdated()
                    scheduleWidgetUpdate(context, wDesc, millisToNextUpdate(lastModified, wDesc.updatePeriodMinutes))
                } catch (e: Throwable) {
                    onImageNotUpdated()
                    throw e
                }
            },
            onNotModified = onImageNotUpdated,
            onFailure = {
                if (forceLoadImage) {
                    updateRemoteViews(context, wDesc, tryLoadImgFromFile(context, wDesc))
                }
                onImageNotUpdated()
            })
}

private fun updateRemoteViews(
        context: Context, wDesc: WidgetDescriptor, tsBitmap: TimestampedBitmap
) {
    val remoteViews = RemoteViews(context.packageName, R.layout.app_widget)
    remoteViews.setOnClickPendingIntent(R.id.img_view_widget, onClickIntent(context))
    if (tsBitmap.isEmpty()) {
        MyLog.w("Set text radar image unavailable")
        remoteViews.setTextViewText(R.id.text_view_widget, "Radar image unavailable. Tap to retry.")
    } else {
        tsBitmap.bitmap?.apply {
            remoteViews.setImageViewBitmap(R.id.img_view_widget, tsBitmap.bitmap)
        }
        tsBitmap.timestamp?.apply {
            remoteViews.setTextViewText(R.id.text_view_widget,
                    getRelativeDateTimeString(context, tsBitmap.timestamp, MINUTE_IN_MILLIS, DAY_IN_MILLIS, 0))
        }
    }
    AppWidgetManager.getInstance(context).updateAppWidget(ComponentName(context, wDesc.providerClass), remoteViews)
    MyLog.i("Updated Remote Views")
}

private fun scheduleWidgetUpdate(context: Context, wDesc : WidgetDescriptor, latencyMillis: Long) {
    val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    val resultCode = jobScheduler.schedule(
            JobInfo.Builder(REFRESH_IMAGE_JOB_ID, ComponentName(context, RefreshImageService::class.java))
                    .setExtras(wDesc.toExtras())
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(latencyMillis)
                    .setOverrideDeadline(HOUR_IN_MILLIS)
                    .build())
    val latencyStr = formatElapsedTime(MILLISECONDS.toSeconds(latencyMillis))
    reportScheduleResult("""refresh radar image after $latencyStr""", resultCode)
}

private fun scheduleUpdateAge(context: Context, widgetDescriptor: WidgetDescriptor) {
    val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    val resultCode = jobScheduler.schedule(
            JobInfo.Builder(UPDATE_AGE_JOB_ID, ComponentName(context, UpdateAgeService::class.java))
                    .setExtras(widgetDescriptor.toExtras())
                    .setPeriodic(MINUTE_IN_MILLIS)
                    .build())
    reportScheduleResult("update age every minute", resultCode)
}

private fun reportScheduleResult(task: String, resultCode: Int) {
    when (resultCode) {
        JobScheduler.RESULT_SUCCESS -> MyLog.i("""Scheduled to $task""")
        JobScheduler.RESULT_FAILURE -> MyLog.e("""Failed to schedule to $task""")
        else -> throw AssertionError("""Unknown scheduler result code $resultCode""")
    }
}

private fun onClickIntent(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
    intent.addCategory("android.intent.category.LAUNCHER")
    intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
    intent.component = ComponentName(context.packageName, MainActivity::class.java.name)
    return PendingIntent.getActivity(context, 0, intent, 0)
}


private fun tryLoadImgFromFile(context: Context, wDesc: WidgetDescriptor) : TimestampedBitmap {
    val gif = File(context.noBackupFilesDir, wDesc.imgFilename())
    if (!gif.exists()) {
        return TimestampedBitmap(null, null)
    }
    val imgBytes = FileInputStream(gif).use { it.readBytes() }
    return wDesc.timestampedBitmapFrom(imgBytes)
}

private fun writeImgTimestamp(context: Context, radarDesc: WidgetDescriptor, tsBitmap: TimestampedBitmap) {
    DataOutputStream(fileOut(file(context, radarDesc.timestampFilename()))).use {
        it.writeLong(tsBitmap.timestamp!!)
    }
}

private fun file(context : Context, name : String) = File(context.noBackupFilesDir, name)

private fun fileOut(f : File) = FileOutputStream(f)

private fun fileIn(f : File) = FileInputStream(f)

/**
 * We use Last-Modified modulo one hour due to DHMZ's broken Last-Modified
 * reporting (it's off by one hour)
 *
 * @param lastModified last modified time in seconds past full hour
 */
private fun millisToNextUpdate(lastModified : Long, updateIntervalMinutes: Long) : Long {
    require(updateIntervalMinutes in 0 until 60, { """updateInterval out of range: $updateIntervalMinutes""" })
    require(lastModified in 0 until SECS_IN_HOUR, { """lastModified out of range: $lastModified""" })

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
