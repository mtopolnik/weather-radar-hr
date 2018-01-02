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
import java.io.IOException
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

private const val SECS_IN_HOUR = 3600L
private const val SECS_IN_MINUTE = 60L
private const val RETRY_PERIOD_MINUTES = 10L
private const val REFRESH_IMAGE_JOB_ID_BASE = 700713272
private const val UPDATE_AGE_JOB_ID_BASE = 700723272
private const val LRADAR_CROP_Y_TOP = 40
private const val KRADAR_CROP_X_RIGHT = 480
private const val EXTRA_WIDGET_DESC_INDEX = "widgetDescIndex"

private val widgetDescriptors = arrayOf(
        WidgetDescriptor("LRadar", "http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar.gif", 10,
                LradarWidgetProvider::class.java,
                R.drawable.lradar_widget_preview,
                { val bitmap = it.toBitmap()
                    TimestampedBitmap(
                            ocrLradarTimestamp(bitmap),
                            createBitmap(bitmap, 0, LRADAR_CROP_Y_TOP, bitmap.width, bitmap.height - LRADAR_CROP_Y_TOP)
            )}),
        WidgetDescriptor("KRadar", "http://vrijeme.hr/kradar.gif", 15,
                KradarWidgetProvider::class.java,
                R.drawable.kradar_widget_preview,
                { val bitmap = it.toBitmap()
                    TimestampedBitmap(
                            ocrKradarTimestamp(bitmap),
                            createBitmap(bitmap, 0, 0, KRADAR_CROP_X_RIGHT, bitmap.height)) })
)

private data class WidgetDescriptor(
        val name: String,
        val url: String,
        val updatePeriodMinutes: Long,
        val providerClass: Class<out AppWidgetProvider>,
        val previewResourceId: Int,
        val timestampedBitmapFrom: (ByteArray) -> TimestampedBitmap
) {
    fun imgFilename() = url.substringAfterLast('/')
    fun timestampFilename() = imgFilename() + ".timestamp"
    fun index() = widgetDescriptors.indexOf(this)
    fun refreshImageJobId() = REFRESH_IMAGE_JOB_ID_BASE + index()
    fun updateAgeJobId() = UPDATE_AGE_JOB_ID_BASE + index()
    fun toExtras() : PersistableBundle {
        val b = PersistableBundle()
        b.putInt(EXTRA_WIDGET_DESC_INDEX, index())
        return b
    }
}

private data class TimestampedBitmap(val timestamp: Long, val bitmap: Bitmap)

fun launchFetchWidgetImages(context : Context) {
    val appContext = context.applicationContext
    widgetDescriptors.forEach { wDesc ->
        val wCtx = WidgetContext(appContext, wDesc)
        if (wCtx.isWidgetInUse()) {
            launch(Unconfined) launch@ {
                wCtx.fetchImageAndUpdateWidget(onlyIfNew = false)
            }
        }
    }
}

class LradarWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetContext(context, widgetDescriptors[0]).onUpdateWidget()
    }
}

class KradarWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetContext(context, widgetDescriptors[1]).onUpdateWidget()
    }
}

class RefreshImageService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        try {
            val wDescIndex = params.extras[EXTRA_WIDGET_DESC_INDEX] as Int
            val wDesc = widgetDescriptors[wDescIndex]
            MyLog.i("RefreshImageService: ${wDesc.name}")
            val wCtx = WidgetContext(applicationContext, wDesc)
            return if (wCtx.isWidgetInUse()) {
                launch(Unconfined) {
                    val lastModified = wCtx.fetchImageAndUpdateWidget(onlyIfNew = true)
                    jobFinished(params, lastModified == null)
                    if (lastModified != null) {
                        wCtx.scheduleWidgetUpdate(millisToNextUpdate(lastModified, wDesc.updatePeriodMinutes))
                    }
                }
                true
            } else {
                wCtx.cancelUpdateAge()
                false
            }
        } catch (e: Throwable) {
            MyLog.e("Error in RefreshImageService", e)
            throw e
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        MyLog.i("RefreshImageService stop job")
        return true
    }

}

class UpdateAgeService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        try {
            val wDescIndex = params.extras[EXTRA_WIDGET_DESC_INDEX] as Int
            val wDesc = widgetDescriptors[wDescIndex]
            MyLog.i("UpdateAgeService: ${wDesc.name}")
            val wCtx = WidgetContext(applicationContext, wDesc)
            if (wCtx.isWidgetInUse()) {
                wCtx.updateRemoteViews(wCtx.readImgAndTimestamp())
            } else {
                wCtx.cancelUpdateAge()
            }
            return false
        } catch (e: Throwable) {
            MyLog.e("error in UpdateAgeService", e)
            throw e
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        MyLog.i("UpdateAgeService stop job")
        return true
    }
}

private class WidgetContext (
        private val context: Context,
        private val wDesc: WidgetDescriptor
) {
    fun providerName() = ComponentName(context, wDesc.providerClass)

    fun isWidgetInUse() =
            AppWidgetManager.getInstance(context).getAppWidgetIds(providerName()).isNotEmpty()

    fun onUpdateWidget() {
        MyLog.w("onUpdate ${wDesc.name}")
        launch(Unconfined) {
            val lastModified = fetchImageAndUpdateWidget(onlyIfNew = false)
            scheduleWidgetUpdate(
                    if (lastModified != null) millisToNextUpdate(lastModified, wDesc.updatePeriodMinutes)
                    else JobInfo.DEFAULT_INITIAL_BACKOFF_MILLIS
            )
        }
        scheduleUpdateAge()
    }

    suspend fun fetchImageAndUpdateWidget(onlyIfNew: Boolean): Long? {
        try {
            val (lastModified, imgBytes) = fetchImage(context, wDesc.url, onlyIfNew)
            if (imgBytes == null) {

                return null
            }
            val tsBitmap = wDesc.timestampedBitmapFrom(imgBytes)
            writeImgAndTimestamp(tsBitmap)
            updateRemoteViews(tsBitmap)
            return lastModified
        } catch (e: ImageFetchException) {
            if (e.cached != null) {
                updateRemoteViews(wDesc.timestampedBitmapFrom(e.cached))
            } else if (!onlyIfNew) {
                MyLog.w("Failed to fetch ${wDesc.imgFilename()}, using preview")
                updateRemoteViews(TimestampedBitmap(
                        0L,
                        (context.resources.getDrawable(wDesc.previewResourceId, null) as BitmapDrawable).bitmap))
            }
            return null
        } catch (t: Throwable) {
            MyLog.e("Widget refresh failure", t)
            return null
        }
    }

    fun updateRemoteViews(tsBitmap: TimestampedBitmap?) {
        val remoteViews = RemoteViews(context.packageName, R.layout.app_widget)
        remoteViews.setOnClickPendingIntent(R.id.img_view_widget, intentLaunchMainActivity(context))
        if (tsBitmap != null) {
            remoteViews.setImageViewBitmap(R.id.img_view_widget, tsBitmap.bitmap)
            remoteViews.setTextViewText(R.id.text_view_widget,
                    getRelativeDateTimeString(context, tsBitmap.timestamp, MINUTE_IN_MILLIS, DAY_IN_MILLIS, 0))
        } else {
            remoteViews.setTextViewText(R.id.text_view_widget, "Radar image unavailable. Tap to retry.")
        }
        AppWidgetManager.getInstance(context).updateAppWidget(WidgetContext(context, wDesc).providerName(), remoteViews)
        MyLog.i("Updated Remote Views")
    }

    fun scheduleWidgetUpdate(latencyMillis: Long) {
        val resultCode = context.jobScheduler().schedule(
                JobInfo.Builder(wDesc.refreshImageJobId(),
                        ComponentName(context, RefreshImageService::class.java))
                        .setExtras(wDesc.toExtras())
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setMinimumLatency(latencyMillis)
                        .setOverrideDeadline(HOUR_IN_MILLIS)
                        .build())
        val latencyStr = formatElapsedTime(MILLISECONDS.toSeconds(latencyMillis))
        reportScheduleResult("refresh radar image after $latencyStr", resultCode)
    }

    fun cancelUpdateAge() {
        MyLog.i("No ${wDesc.name} widget in use, cancelling scheduled jobs")
        context.jobScheduler().cancel(wDesc.updateAgeJobId())
    }

    fun readImgAndTimestamp() : TimestampedBitmap? {
        val file = context.file(wDesc.timestampFilename())
        return try {
            file.dataIn().use { TimestampedBitmap(it.readLong(), BitmapFactory.decodeStream(it)) }
        } catch (e : Exception) {
            null
        }
    }

    private fun scheduleUpdateAge() {
        val resultCode = context.jobScheduler().schedule(
                JobInfo.Builder(wDesc.updateAgeJobId(), ComponentName(context, UpdateAgeService::class.java))
                        .setExtras(wDesc.toExtras())
                        .setPeriodic(MINUTE_IN_MILLIS)
                        .build())
        reportScheduleResult("update age every minute", resultCode)
    }

    private fun writeImgAndTimestamp(tsBitmap: TimestampedBitmap) {
        val fname = wDesc.timestampFilename()
        val growingFile = context.file(fname + ".growing")
        growingFile.dataOut().use {
            it.writeLong(tsBitmap.timestamp)
            tsBitmap.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        if (!growingFile.renameTo(context.file(fname))) {
            throw IOException("Couldn't rename $growingFile")
        }
    }
}

private fun Context.jobScheduler() =
        getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

private fun intentLaunchMainActivity(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
    intent.addCategory("android.intent.category.LAUNCHER")
    intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
    intent.component = ComponentName(context.packageName, MainActivity::class.java.name)
    return PendingIntent.getActivity(context, 0, intent, 0)
}

private fun reportScheduleResult(task: String, resultCode: Int) {
    when (resultCode) {
        JobScheduler.RESULT_SUCCESS -> MyLog.i("Scheduled to $task")
        JobScheduler.RESULT_FAILURE -> MyLog.e("Failed to schedule to $task")
        else -> throw AssertionError("Unknown scheduler result code $resultCode")
    }
}

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
