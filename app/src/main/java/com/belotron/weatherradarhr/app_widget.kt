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
import android.graphics.BitmapFactory
import android.text.format.DateUtils.formatElapsedTime
import android.widget.RemoteViews
import com.belotron.weatherradarhr.ImageRequest.sendImageRequest
import java.lang.System.currentTimeMillis
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

const val SECS_IN_HOUR = 3600L
const val SECS_IN_MINUTE = 60L
const val IMAGE_UPDATE_PERIOD_MINUTES = 10L
const val RETRY_PERIOD_MINUTES = 10L
const val SCHEDULED_JOB_ID = 700713272

class MyWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        MyLog.w("onUpdate")
        updateWidgetAndScheduleNext(context.applicationContext, useIfModifiedSince = false)
    }
}

class UpdateWidgetService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        MyLog.i("UpdateWidgetService start job")
        updateWidgetAndScheduleNext(applicationContext, onCompletion = { jobFinished(params, false) })
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        MyLog.i("UpdateWidgetService stop job")
        return true
    }
}

fun updateWidgetAndScheduleNext(context: Context,
                                useIfModifiedSince : Boolean = true,
                                alwaysScheduleNext: Boolean = true,
                                onCompletion : () -> Unit = {}
) {
    sendImageRequest(context, APPWIDGET_IMG_URL,
            useIfModifiedSince = useIfModifiedSince,
            //                      seconds past full hour
            onSuccess = { imgBytes, lastModified ->
                updateRemoteViews(context, imgBytes)
                scheduleWidgetUpdate(true, context, millisToNextUpdate(lastModified, IMAGE_UPDATE_PERIOD_MINUTES))
            },
            onNotModified = {
                scheduleWidgetUpdate(alwaysScheduleNext, context, RETRY_PERIOD_MINUTES)
            },
            onFailure = {
                MyLog.w("""Failed to update radar image. Will retry in $RETRY_PERIOD_MINUTES minutes.""")
                scheduleWidgetUpdate(alwaysScheduleNext, context, RETRY_PERIOD_MINUTES)
            },
            onCompletion = onCompletion)
}

private fun hourRelativeCurrentTime() : Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("GMT"))
    cal.timeInMillis = currentTimeMillis()
    return SECS_IN_MINUTE * cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND)
}

/**
 * @param lastModified last modified time, seconds past full hour
 */
private fun millisToNextUpdate(lastModified : Long, updateIntervalMinutes: Long) : Long {
    require(updateIntervalMinutes in 0 until 60, { """updateInterval out of range: $updateIntervalMinutes""" })
    require(lastModified in 0 until SECS_IN_HOUR, { """lastModified out of range: $lastModified""" })

    val now = hourRelativeCurrentTime()
    val modifiedSecondsAgo = if (now >= lastModified) now - lastModified
                             else (now + SECS_IN_HOUR) - lastModified
    val proposedDelay = (updateIntervalMinutes + 1) * SECS_IN_MINUTE - modifiedSecondsAgo
    return SECONDS.toMillis(if (proposedDelay > 0) proposedDelay
                            else RETRY_PERIOD_MINUTES * SECS_IN_MINUTE)
}

private fun scheduleWidgetUpdate(reallyDoIt: Boolean, context: Context, minLatency: Long) {
    if (!reallyDoIt) {
        return
    }
    val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    jobScheduler.cancel(SCHEDULED_JOB_ID)
    val resultCode = jobScheduler.schedule(
            JobInfo.Builder(SCHEDULED_JOB_ID, ComponentName(context, UpdateWidgetService::class.java))
                    .setMinimumLatency(minLatency)
                    .build())
    val minLatencyStr = formatElapsedTime(MILLISECONDS.toSeconds(minLatency))
    when (resultCode) {
        JobScheduler.RESULT_SUCCESS -> MyLog.i(
                """Scheduled to update widget after $minLatencyStr minutes""")
        JobScheduler.RESULT_FAILURE -> MyLog.e(
                """Failed to schedule widget update after $minLatencyStr minutes""")
        else -> throw AssertionError("""Unknown scheduler result code $resultCode""")
    }
}

private fun updateRemoteViews(context : Context, bytes: ByteArray?) {
    if (bytes == null) return
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options())
    val remoteViews = RemoteViews(context.packageName, R.layout.app_widget)
    remoteViews.setOnClickPendingIntent(R.id.img_view_widget, onClickIntent(context))
    remoteViews.setImageViewBitmap(R.id.img_view_widget, bitmap)
    AppWidgetManager.getInstance(context)
            .updateAppWidget(ComponentName(context, MyWidgetProvider::class.java), remoteViews)
    MyLog.i("Updated App Widget")
}

private fun onClickIntent(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
    intent.addCategory("android.intent.category.LAUNCHER")
    intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
    intent.component = ComponentName(context.packageName, MainActivity::class.java.name)
    return PendingIntent.getActivity(context, 0, intent, 0)
}
