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
import android.text.format.DateUtils.DAY_IN_MILLIS
import android.text.format.DateUtils.HOUR_IN_MILLIS
import android.text.format.DateUtils.MINUTE_IN_MILLIS
import android.text.format.DateUtils.formatElapsedTime
import android.text.format.DateUtils.getRelativeDateTimeString
import android.widget.RemoteViews
import com.belotron.weatherradarhr.ImageRequest.sendImageRequest
import com.belotron.weatherradarhr.KradarOcr.ocrKradarTimestamp
import com.belotron.weatherradarhr.LradarOcr.ocrLradarTimestamp
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
private const val ACTIVE_RADAR_INDEX = 1

private var imgTimestamp: Long? = null

private val radarDescriptors = arrayOf(
        Descriptor("http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar.gif", 10, {
            val bitmap = it.toBitmap()
            TimestampedBitmap(
                    createBitmap(bitmap, 0, LRADAR_CROP_Y_TOP, bitmap.width, bitmap.height - LRADAR_CROP_Y_TOP),
                    ocrLradarTimestamp(bitmap)
            )}),
        Descriptor("http://vrijeme.hr/kradar.gif", 15, {
            val bitmap = it.toBitmap()
            TimestampedBitmap(
                    createBitmap(bitmap, 0, 0, KRADAR_CROP_X_RIGHT, bitmap.height),
                    ocrKradarTimestamp(bitmap)) })
)

private data class Descriptor(
        val url : String,
        val updatePeriodMinutes : Long,
        val timestampedBitmapFrom: (ByteArray) -> TimestampedBitmap
) {
    fun filename() = url.substringAfterLast('/')

}

private data class TimestampedBitmap(val bitmap : Bitmap?, val timestamp : Long?) {
    fun isEmpty() = bitmap == null && timestamp == null
}

class MyWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        MyLog.w("onUpdate")
        updateWidgetAndScheduleNext(context.applicationContext, forceLoadImage = true)
        scheduleUpdateAge(context.applicationContext)
    }
}

class RefreshImageService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        MyLog.i("RefreshImageService start job")
        updateWidgetAndScheduleNext(applicationContext,
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
        initOcr(applicationContext)
        updateRemoteViews(applicationContext,
                if (imgTimestamp != null) TimestampedBitmap(null, imgTimestamp)
                else tryLoadImgFromFile(applicationContext))
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean {
        MyLog.i("UpdateAgeService stop job")
        return true
    }
}

fun updateWidgetAndScheduleNext(context: Context,
                                forceLoadImage: Boolean = false,
                                onImageUpdated: () -> Unit = {},
                                onImageNotUpdated: () -> Unit = {}
) {
    val radarDesc = radarDescriptors[ACTIVE_RADAR_INDEX]
    sendImageRequest(context, radarDesc.url,
            useIfModifiedSince = !forceLoadImage,
            //                      seconds past full hour
            onSuccess = { imgBytes, lastModified ->
                try {
                    FileOutputStream(File(context.noBackupFilesDir, radarDesc.filename())).use { it.write(imgBytes) }
                    val tsBitmap = radarDesc.timestampedBitmapFrom(imgBytes)
                    imgTimestamp = tsBitmap.timestamp
                    updateRemoteViews(context, tsBitmap)
                    onImageUpdated()
                    scheduleWidgetUpdate(context, millisToNextUpdate(lastModified, radarDesc.updatePeriodMinutes))
                } catch (e: Throwable) {
                    onImageNotUpdated()
                    throw e
                }
            },
            onNotModified = onImageNotUpdated,
            onFailure = {
                if (forceLoadImage) {
                    updateRemoteViews(context, tryLoadImgFromFile(context))
                }
                onImageNotUpdated()
            })
}

private fun updateRemoteViews(context : Context, tsBitmap: TimestampedBitmap) {
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
    AppWidgetManager.getInstance(context)
            .updateAppWidget(ComponentName(context, MyWidgetProvider::class.java), remoteViews)
    MyLog.i("Updated Remote Views")
}

private fun tryLoadImgFromFile(context: Context) : TimestampedBitmap {
    val radarDesc = radarDescriptors[ACTIVE_RADAR_INDEX]
    val gif = File(context.noBackupFilesDir, radarDesc.filename())
    if (!gif.exists()) {
        return TimestampedBitmap(null, null)
    }
    val imgBytes = FileInputStream(gif).use { it.readBytes() }
    val tsBitmap = radarDesc.timestampedBitmapFrom(imgBytes)
    imgTimestamp = tsBitmap.timestamp
    return tsBitmap
}

private fun scheduleWidgetUpdate(context: Context, latencyMillis: Long) {
    val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    val resultCode = jobScheduler.schedule(
            JobInfo.Builder(REFRESH_IMAGE_JOB_ID, ComponentName(context, RefreshImageService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(latencyMillis)
                    .setOverrideDeadline(HOUR_IN_MILLIS)
                    .build())
    val latencyStr = formatElapsedTime(MILLISECONDS.toSeconds(latencyMillis))
    reportScheduleResult("""refresh radar image after $latencyStr""", resultCode)
}

private fun scheduleUpdateAge(context: Context) {
    val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    val resultCode = jobScheduler.schedule(
            JobInfo.Builder(UPDATE_AGE_JOB_ID, ComponentName(context, UpdateAgeService::class.java))
                    .setPeriodic(MINUTE_IN_MILLIS)
                    .build())
    reportScheduleResult("update age every minute", resultCode)
}

private fun reportScheduleResult(task: String, resultCode: Int) {
    when (resultCode) {
        JobScheduler.RESULT_SUCCESS -> MyLog.i(
                """Scheduled to $task""")
        JobScheduler.RESULT_FAILURE -> MyLog.e(
                """Failed to schedule to $task""")
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
