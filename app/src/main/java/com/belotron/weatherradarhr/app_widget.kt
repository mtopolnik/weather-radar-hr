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
import android.text.format.DateUtils.DAY_IN_MILLIS
import android.text.format.DateUtils.MINUTE_IN_MILLIS
import android.text.format.DateUtils.formatElapsedTime
import android.text.format.DateUtils.getRelativeDateTimeString
import android.widget.RemoteViews
import com.belotron.weatherradarhr.ImageRequest.sendImageRequest
import com.belotron.weatherradarhr.KradarOcr.ocrKradarTimestamp
import com.belotron.weatherradarhr.LradarOcr.ocrLradarTimestamp
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

private val radars = arrayOf(
        Descriptor("http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar.gif", 10,
                { TimestampedBitmap(
                        Bitmap.createBitmap(it, 0, LRADAR_CROP_Y_TOP, it.width, it.height - LRADAR_CROP_Y_TOP),
                        ocrLradarTimestamp(it)
                )}),
        Descriptor("http://vrijeme.hr/kradar.gif", 15,
                { TimestampedBitmap(
                        Bitmap.createBitmap(it, 0, 0, KRADAR_CROP_X_RIGHT, it.height ),
                        ocrKradarTimestamp(it))
                })
)

private data class Descriptor(
        val url : String,
        val updatePeriodMinutes : Long,
        val toTimestampedBitmap : (Bitmap) -> TimestampedBitmap
)

private data class TimestampedBitmap(val bitmap : Bitmap, val timestamp : Long)

private var radarBitmap: TimestampedBitmap? = null

class MyWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        MyLog.w("onUpdate")
        initOcr(context)
        updateWidgetAndScheduleNext(context.applicationContext, useIfModifiedSince = false)
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        jobScheduler.cancel(UPDATE_AGE_JOB_ID)
        jobScheduler.schedule(
                JobInfo.Builder(UPDATE_AGE_JOB_ID, ComponentName(context, UpdateAgeService::class.java))
                        .setPeriodic(MINUTE_IN_MILLIS)
                        .build())
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
        if (radarBitmap != null) {
            updateRemoteViews(applicationContext)
        }
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean {
        MyLog.i("UpdateAgeService stop job")
        return true
    }
}

fun updateWidgetAndScheduleNext(context: Context,
                                useIfModifiedSince: Boolean = true,
                                onImageUpdated: () -> Unit = {},
                                onImageNotUpdated: () -> Unit = {}
) {
    val radarDesc = radars[ACTIVE_RADAR_INDEX]
    sendImageRequest(context, radarDesc.url,
            useIfModifiedSince = useIfModifiedSince,
            //                      seconds past full hour
            onSuccess = { imgBytes, lastModified ->
                radarBitmap = radarDesc.toTimestampedBitmap(imgBytes.toBitmap())
                updateRemoteViews(context)
                scheduleWidgetUpdate(true, context, millisToNextUpdate(lastModified, radarDesc.updatePeriodMinutes))
                onImageUpdated()
            },
            onNotModified = onImageNotUpdated,
            onFailure = onImageNotUpdated)
}

private fun updateRemoteViews(context : Context) {
    val lradarBitmap = radarBitmap!!
    val remoteViews = RemoteViews(context.packageName, R.layout.app_widget)
    remoteViews.setOnClickPendingIntent(R.id.img_view_widget, onClickIntent(context))
    val ageString = getRelativeDateTimeString(context, lradarBitmap.timestamp, MINUTE_IN_MILLIS, DAY_IN_MILLIS, 0)
    remoteViews.setImageViewBitmap(R.id.img_view_widget, lradarBitmap.bitmap)
    remoteViews.setTextViewText(R.id.text_view_widget, ageString)
    AppWidgetManager.getInstance(context)
            .updateAppWidget(ComponentName(context, MyWidgetProvider::class.java), remoteViews)
    MyLog.i("Updated App Widget")
}

private fun scheduleWidgetUpdate(reallyDoIt: Boolean, context: Context, latencyMillis: Long) {
    if (!reallyDoIt) {
        return
    }
    val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    val resultCode = jobScheduler.schedule(
            JobInfo.Builder(REFRESH_IMAGE_JOB_ID, ComponentName(context, RefreshImageService::class.java))
                    .setMinimumLatency(latencyMillis)
                    .build())
    reportScheduleResult(latencyMillis, resultCode)
}

private fun reportScheduleResult(minLatency: Long, resultCode: Int) {
    val minLatencyStr = formatElapsedTime(MILLISECONDS.toSeconds(minLatency))
    when (resultCode) {
        JobScheduler.RESULT_SUCCESS -> MyLog.i(
                """Scheduled to update widget after $minLatencyStr minutes""")
        JobScheduler.RESULT_FAILURE -> MyLog.e(
                """Failed to schedule widget update after $minLatencyStr minutes""")
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
