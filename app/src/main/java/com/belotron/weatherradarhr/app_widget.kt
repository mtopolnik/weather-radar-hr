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
import android.content.Intent.ACTION_MAIN
import android.graphics.Bitmap
import android.graphics.Bitmap.createBitmap
import android.graphics.BitmapFactory
import android.os.PersistableBundle
import android.text.format.DateUtils.HOUR_IN_MILLIS
import android.text.format.DateUtils.MINUTE_IN_MILLIS
import android.text.format.DateUtils.formatElapsedTime
import android.widget.RemoteViews
import com.belotron.weatherradarhr.FetchPolicy.ONLY_IF_NEW
import com.belotron.weatherradarhr.FetchPolicy.UP_TO_DATE
import com.belotron.weatherradarhr.KradarOcr.ocrKradarTimestamp
import com.belotron.weatherradarhr.LradarOcr.ocrLradarTimestamp
import com.belotron.weatherradarhr.gifdecode.GifDecodeException
import com.belotron.weatherradarhr.gifdecode.ParsedGif
import java.io.IOException
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

private const val SECS_IN_HOUR = 3600L
private const val SECS_IN_MINUTE = 60L
private const val UPDATE_AGE_PERIOD_MINUTES = 3
private const val RETRY_PERIOD_MINUTES = 10L
private const val REFRESH_IMAGE_JOB_ID_BASE = 700713272
private const val UPDATE_AGE_JOB_ID_BASE = 700723272
private const val LRADAR_CROP_Y_TOP = 40
private const val KRADAR_CROP_X_RIGHT = 480
private const val EXTRA_WIDGET_DESC_INDEX = "widgetDescIndex"

@Suppress("MoveLambdaOutsideParentheses")
private val widgetDescriptors = arrayOf(
        WidgetDescriptor("LRadar", "http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar.gif", 10,
                LradarWidgetProvider::class.java,
                R.drawable.lradar_widget_preview,
                { bitmap, isOffline ->
                    TimestampedBitmap(
                            ocrLradarTimestamp(bitmap),
                            isOffline,
                            createBitmap(bitmap, 0, LRADAR_CROP_Y_TOP, bitmap.width, bitmap.height - LRADAR_CROP_Y_TOP)
            )}),
        WidgetDescriptor("KRadar", "http://vrijeme.hr/kradar.gif", 15,
                KradarWidgetProvider::class.java,
                R.drawable.kradar_widget_preview,
                { bitmap, isOffline ->
                    TimestampedBitmap(
                            ocrKradarTimestamp(bitmap),
                            isOffline,
                            createBitmap(bitmap, 0, 0, KRADAR_CROP_X_RIGHT, bitmap.height)) })
)

private data class WidgetDescriptor(
        val name: String,
        val url: String,
        val updatePeriodMinutes: Long,
        val providerClass: Class<out AppWidgetProvider>,
        val previewResourceId: Int,
        val toTimestampedBitmap: (Bitmap, Boolean) -> TimestampedBitmap
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

private data class TimestampedBitmap(val timestamp: Long, val isOffline: Boolean, val bitmap: Bitmap)

fun Context.startFetchWidgetImages() {
    val appContext = applicationContext
    widgetDescriptors.forEach { wDesc ->
        val wCtx = WidgetContext(appContext, wDesc)
        if (wCtx.isWidgetInUse) {
            start {
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
            info { "RefreshImageService: ${wDesc.name}" }
            val wCtx = WidgetContext(applicationContext, wDesc)
            return if (wCtx.isWidgetInUse) {
                start {
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
            error(e) { "Error in RefreshImageService" }
            jobFinished(params, true)
            return false
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        info { "RefreshImageService stop job" }
        return true
    }
}

class UpdateAgeService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        try {
            val wDescIndex = params.extras[EXTRA_WIDGET_DESC_INDEX] as Int
            val wDesc = widgetDescriptors[wDescIndex]
            info { "UpdateAgeService: ${wDesc.name}" }
            with (WidgetContext(applicationContext, wDesc)) {
                if (isWidgetInUse) {
                    updateRemoteViews(readImgAndTimestamp())
                } else {
                    cancelUpdateAge()
                }
            }
            return false
        } catch (e: Throwable) {
            error(e) { "error in UpdateAgeService" }
            return false
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        info { "UpdateAgeService stop job" }
        return true
    }
}

private class WidgetContext (
        private val context: Context,
        private val wDesc: WidgetDescriptor
) {
    val providerName = ComponentName(context, wDesc.providerClass)

    val isWidgetInUse get() = context.appWidgetManager.getAppWidgetIds(providerName).isNotEmpty()

    fun onUpdateWidget() {
        warn { "onUpdate ${wDesc.name}" }
        updateRemoteViews(null)
        start {
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
            try {
                val (lastModified, parsedGif) = fetchUrl(context, wDesc.url, if (onlyIfNew) ONLY_IF_NEW else UP_TO_DATE)
                if (parsedGif == null) {
                    // This may happen only with `onlyIfNew == true`
                    return null
                }
                val tsBitmap = wDesc.timestampedBitmapFrom(parsedGif, false)
                writeImgAndTimestamp(tsBitmap)
                updateRemoteViews(tsBitmap)
                return lastModified
            } catch (e: ImageFetchException) {
                if (e.cached != null) {
                    updateRemoteViews(wDesc.timestampedBitmapFrom(e.cached, true))
                } else if (!onlyIfNew) {
                    warn { "Failed to fetch ${wDesc.imgFilename()}" }
                }
            }
        } catch (t: Throwable) {
            error(t) { "Failed to refresh widget ${wDesc.imgFilename()}" }
        }
        return null
    }

    private fun WidgetDescriptor.timestampedBitmapFrom(parsedGif: ParsedGif, isOffline: Boolean): TimestampedBitmap {
        try {
            return wDesc.toTimestampedBitmap(parsedGif.toBitmap(), isOffline)
        } catch (e: GifDecodeException) {
            error { "GIF decoding error" }
            context.invalidateCache(url)
            throw e
        }
    }

    fun updateRemoteViews(tsBitmap: TimestampedBitmap?) {
        val remoteViews = RemoteViews(context.packageName, R.layout.app_widget)
        with(remoteViews) {
            setOnClickPendingIntent(R.id.img_view_widget, intentLaunchMainActivity(context))
            if (tsBitmap != null) tsBitmap.also {
                setImageViewBitmap(R.id.img_view_widget, it.bitmap)
                setAgeText(context, it.timestamp, it.isOffline)
            } else {
                setImageViewResource(R.id.img_view_widget, wDesc.previewResourceId)
                setRedText(context.resources.getString(R.string.img_unavailable))
            }
        }
        context.appWidgetManager.updateAppWidget(WidgetContext(context, wDesc).providerName, remoteViews)
        info { "Updated Remote Views" }
    }

    fun scheduleWidgetUpdate(latencyMillis: Long) {
        val resultCode = context.jobScheduler.schedule(
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
        info { "No ${wDesc.name} widget in use, cancelling scheduled jobs" }
        context.jobScheduler.cancel(wDesc.updateAgeJobId())
    }

    private fun scheduleUpdateAge() {
        val resultCode = context.jobScheduler.schedule(
                JobInfo.Builder(wDesc.updateAgeJobId(), ComponentName(context, UpdateAgeService::class.java))
                        .setExtras(wDesc.toExtras())
                        .setPeriodic(UPDATE_AGE_PERIOD_MINUTES * MINUTE_IN_MILLIS)
                        .build())
        reportScheduleResult("update age every minute", resultCode)
    }

    fun readImgAndTimestamp() : TimestampedBitmap? {
        val file = context.file(wDesc.timestampFilename())
        return try {
            file.dataIn().use { TimestampedBitmap(it.readLong(), it.readBoolean(), BitmapFactory.decodeStream(it)) }
        } catch (e : Exception) {
            null
        }
    }

    private fun writeImgAndTimestamp(tsBitmap: TimestampedBitmap) {
        val fname = wDesc.timestampFilename()
        val growingFile = context.file("$fname.growing")
        growingFile.dataOut().use {
            it.writeLong(tsBitmap.timestamp)
            it.writeBoolean(tsBitmap.isOffline)
            tsBitmap.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        if (!growingFile.renameTo(context.file(fname))) {
            throw IOException("Couldn't rename $growingFile")
        }
    }
}

private val Context.jobScheduler get() = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

private val Context.appWidgetManager get() = AppWidgetManager.getInstance(this)

private fun intentLaunchMainActivity(context: Context): PendingIntent {
    val launchIntent = with(Intent(ACTION_MAIN)) {
        addCategory(Intent.CATEGORY_LAUNCHER)
        component = ComponentName(context.packageName, MainActivity::class.java.name)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return PendingIntent.getActivity(context, 0, launchIntent, 0)
}

private fun reportScheduleResult(task: String, resultCode: Int) {
    when (resultCode) {
        JobScheduler.RESULT_SUCCESS -> info { "Scheduled to $task" }
        JobScheduler.RESULT_FAILURE -> error("Failed to schedule to $task")
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
