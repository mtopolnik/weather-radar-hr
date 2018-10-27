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
import com.belotron.weatherradarhr.CcOption.CC_PRIVATE
import com.belotron.weatherradarhr.FetchPolicy.ONLY_IF_NEW
import com.belotron.weatherradarhr.FetchPolicy.UP_TO_DATE
import com.belotron.weatherradarhr.KradarOcr.ocrKradarTimestamp
import com.belotron.weatherradarhr.LradarOcr.ocrLradarTimestamp
import com.belotron.weatherradarhr.gifdecode.ImgDecodeException
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
private const val KRADAR_CROP_Y_HEIGHT = 480
private const val EXTRA_WIDGET_DESC_INDEX = "widgetDescIndex"
private const val EXTRA_BACKOFF_LATENCY = "backoffLatency"

@Suppress("MoveLambdaOutsideParentheses")
private val widgetDescriptors = arrayOf(
        WidgetDescriptor("LRadar", "http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar.gif", 10,
                LradarWidgetProvider::class.java,
                R.drawable.lradar_widget_preview,
                { bitmap, isOffline ->
                    TimestampedBitmap(
                            ocrLradarTimestamp(bitmap),
                            isOffline,
                            bitmap.crop(0, LRADAR_CROP_Y_TOP, bitmap.width, bitmap.height - LRADAR_CROP_Y_TOP)
            )}),
        WidgetDescriptor("KRadar", "http://vrijeme.hr/kompozit-stat.png", 10,
                KradarWidgetProvider::class.java,
                R.drawable.kradar_widget_preview,
                { bitmap, isOffline ->
                    TimestampedBitmap(
                            ocrKradarTimestamp(bitmap),
                            isOffline,
                            bitmap.crop(0, 0, bitmap.width, KRADAR_CROP_Y_HEIGHT)) })
)

private fun Bitmap.crop(x: Int, y: Int, width: Int, height: Int) = createBitmap(this, x, y, width, height)

private data class WidgetDescriptor(
        val name: String,
        val url: String,
        val updatePeriodMinutes: Long,
        val providerClass: Class<out AppWidgetProvider>,
        val previewResourceId: Int,
        val toTimestampedBitmap: (Bitmap, Boolean) -> TimestampedBitmap
) {
    val imgFilename get() = url.substringAfterLast('/')
    val timestampFilename get() = "$imgFilename.timestamp"
    val index get() = widgetDescriptors.indexOf(this)
    val refreshImageJobId get() = REFRESH_IMAGE_JOB_ID_BASE + index
    val updateAgeJobId get() = UPDATE_AGE_JOB_ID_BASE + index
    fun toExtras(backoffLatency: Long) = PersistableBundle().apply {
        putInt(EXTRA_WIDGET_DESC_INDEX, index)
        putLong(EXTRA_BACKOFF_LATENCY, backoffLatency)
    }
}

private data class TimestampedBitmap(val timestamp: Long, val isOffline: Boolean, val bitmap: Bitmap)

fun startFetchWidgetImages() {
    widgetDescriptors
            .map { WidgetContext(appContext, it) }
            .filter { it.isWidgetInUse }
            .forEach { wCtx -> wCtx.apply {
                appCoroScope.start {
                    fetchImageAndUpdateWidget(onlyIfNew = false)
                }
                if (appContext.jobScheduler.allPendingJobs.none { it.id == wDesc.refreshImageJobId }) {
                    info(CC_PRIVATE) { "startFetchWidgetImages ${wDesc.name}: refresh not scheduled, fixing" }
                    scheduleJustInCase()
                }
            } }
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
        val widgetName = params.widgetName
        val backoffLatency = params.extras.backoffLatency
        val logHead = "RefreshImage $widgetName"
        info(CC_PRIVATE) { "$logHead: start job" }
        try {
            val wDesc = params.widgetDescriptor
            val wCtx = WidgetContext(applicationContext, wDesc)
            return if (wCtx.isWidgetInUse) {
                wCtx.scheduleJustInCase()
                appCoroScope.start {
                    try {
                        val lastModified = wCtx.fetchImageAndUpdateWidget(onlyIfNew = true)
                        jobFinished(params, lastModified == null)
                        if (lastModified != null) {
                            val mmss = formatElapsedTime(MILLISECONDS.toSeconds(lastModified))
                            info(CC_PRIVATE) { "$logHead: success, last modified $mmss" }
                            wCtx.scheduleWidgetUpdate(true, millisToNextUpdate(lastModified, wDesc.updatePeriodMinutes))
                        } else {
                            wCtx.scheduleWidgetUpdate(false, backoffLatency)
                            info(CC_PRIVATE) { "$logHead: no new image" }
                        }
                    } catch (t: Throwable) {
                        severe(CC_PRIVATE, t) { "$logHead: error in coroutine" }
                    }
                }
                true
            } else {
                wCtx.cancelUpdateAge()
                false
            }
        } catch (e: Throwable) {
            severe(CC_PRIVATE, e) { "$logHead: error on main thread" }
            jobFinished(params, true)
            return false
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        info(CC_PRIVATE) { "RefreshImage ${params.widgetName}: stop job" }
        return true
    }
}

class UpdateAgeService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val logHead = "UpdateAge ${params.widgetName}"
        info { "$logHead: start job" }
        try {
            val wDesc = params.widgetDescriptor
            with (WidgetContext(applicationContext, wDesc)) {
                if (isWidgetInUse) {
                    updateRemoteViews(readImgAndTimestamp())
                } else {
                    cancelUpdateAge()
                }
            }
        } catch (e: Throwable) {
            severe(e) { "$logHead: error" }
        }
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean {
        info { "UpdateAge ${params.widgetName}: stop job" }
        return true
    }
}

private class WidgetContext (
        private val context: Context,
        val wDesc: WidgetDescriptor
) {
    val providerName = ComponentName(context, wDesc.providerClass)

    val isWidgetInUse get() = context.appWidgetManager.getAppWidgetIds(providerName).isNotEmpty()

    fun onUpdateWidget() {
        val logHead = "onUpdateWidget ${wDesc.name}"
        info(CC_PRIVATE) { "$logHead: initial image fetch" }
        try {
            scheduleJustInCase()
            updateRemoteViews(null)
            appCoroScope.start {
                try {
                    val lastModified = fetchImageAndUpdateWidget(onlyIfNew = false)
                    if (lastModified != null) {
                        info(CC_PRIVATE) { "$logHead: success" }
                        scheduleWidgetUpdate(true, millisToNextUpdate(lastModified, wDesc.updatePeriodMinutes))
                    } else {
                        info(CC_PRIVATE) { "$logHead: failed, scheduling to retry" }
                        scheduleWidgetUpdate(false, JobInfo.DEFAULT_INITIAL_BACKOFF_MILLIS)
                    }
                } catch (t: Throwable) {
                    severe(CC_PRIVATE, t) { "$logHead: error in coroutine" }
                }
            }
            scheduleUpdateAge()
        } catch (t: Throwable) {
            severe(CC_PRIVATE, t) { "$logHead: error on main thread" }
        }
    }

    suspend fun fetchImageAndUpdateWidget(onlyIfNew: Boolean): Long? {
        try {
            try {
                val (lastModified, bitmap) = context.fetchBitmap(wDesc.url, if (onlyIfNew) ONLY_IF_NEW else UP_TO_DATE)
                if (bitmap == null) {
                    // This may happen only with `onlyIfNew == true`
                    return null
                }
                val tsBitmap = wDesc.toTimestampedBitmap(bitmap, false)
                writeImgAndTimestamp(tsBitmap)
                updateRemoteViews(tsBitmap)
                return lastModified
            } catch (e: ImageFetchException) {
                if (e.cached != null) {
                    updateRemoteViews(wDesc.toTimestampedBitmap(e.cached as Bitmap, true))
                } else if (!onlyIfNew) {
                    warn(CC_PRIVATE) { "Failed to fetch ${wDesc.imgFilename}" }
                }
            } catch (e: ImgDecodeException) {
                severe(CC_PRIVATE) { "Image decoding error for ${wDesc.name}" }
                context.invalidateCache(wDesc.url)
                throw e
            }
        } catch (t: Throwable) {
            severe(CC_PRIVATE, t) { "Failed to refresh widget ${wDesc.name}" }
        }
        return null
    }

    // Provisionally schedules the refresh job in case OS kills our process
    // before we get the network result
    fun scheduleJustInCase() {
        scheduleWidgetUpdate(true, RETRY_PERIOD_MINUTES * MINUTE_IN_MILLIS)
    }

    fun updateRemoteViews(tsBitmap: TimestampedBitmap?) {
        val remoteViews = RemoteViews(context.packageName, R.layout.app_widget)
        with(remoteViews) {
            setOnClickPendingIntent(R.id.img_view_widget, context.intentLaunchMainActivity())
            tsBitmap?.also {
                setImageViewBitmap(R.id.img_view_widget, it.bitmap)
                setAgeText(context, it.timestamp, it.isOffline)
            } ?: run {
                setImageViewResource(R.id.img_view_widget, wDesc.previewResourceId)
                setRedText(context.resources.getString(R.string.img_unavailable))
            }
        }
        context.appWidgetManager.updateAppWidget(WidgetContext(context, wDesc).providerName, remoteViews)
        info { "Updated Remote Views for ${wDesc.name}" }
    }

    fun scheduleWidgetUpdate(success: Boolean, latencyMillis: Long) {
        val jobInfo = JobInfo.Builder(wDesc.refreshImageJobId, ComponentName(context, RefreshImageService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(latencyMillis)
            .setOverrideDeadline(HOUR_IN_MILLIS)
            .setExtras(wDesc.toExtras(if (success) JobInfo.DEFAULT_INITIAL_BACKOFF_MILLIS else 2 * latencyMillis))
            .build()
        val resultCode = context.jobScheduler.schedule(jobInfo)
        val latencyStr = formatElapsedTime(MILLISECONDS.toSeconds(latencyMillis))
        reportScheduleResult("refresh image for ${wDesc.name} after $latencyStr minutes:seconds", resultCode)
    }

    fun cancelUpdateAge() {
        info(CC_PRIVATE) { "No ${wDesc.name} widget in use, cancelling scheduled jobs" }
        with(context.jobScheduler) {
            cancel(wDesc.updateAgeJobId)
            cancel(wDesc.refreshImageJobId)
        }
    }

    private fun scheduleUpdateAge() {
        val resultCode = context.jobScheduler.schedule(
                JobInfo.Builder(wDesc.updateAgeJobId, ComponentName(context, UpdateAgeService::class.java))
                        .setExtras(wDesc.toExtras(0L))
                        .setPeriodic(UPDATE_AGE_PERIOD_MINUTES * MINUTE_IN_MILLIS)
                        .build())
        reportScheduleResult("update age of ${wDesc.name} every three minutes", resultCode)
    }

    fun readImgAndTimestamp() : TimestampedBitmap? {
        val file = context.fileInCache(wDesc.timestampFilename)
        return try {
            file.dataIn().use { TimestampedBitmap(it.readLong(), it.readBoolean(), BitmapFactory.decodeStream(it)) }
        } catch (e : Exception) {
            null
        }
    }

    private fun writeImgAndTimestamp(tsBitmap: TimestampedBitmap) {
        val fname = wDesc.timestampFilename
        val growingFile = context.fileInCache("$fname.growing")
        growingFile.dataOut().use {
            it.writeLong(tsBitmap.timestamp)
            it.writeBoolean(tsBitmap.isOffline)
            tsBitmap.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        if (!growingFile.renameTo(context.fileInCache(fname))) {
            throw IOException("Couldn't rename $growingFile")
        }
    }
}

private val JobParameters.widgetDescriptor: WidgetDescriptor
    get() = widgetDescriptors[extras[EXTRA_WIDGET_DESC_INDEX] as Int]

private val JobParameters.widgetName get() = runOrNull { widgetDescriptor.name } ?: ""

private val PersistableBundle.backoffLatency: Long get() =
    this[EXTRA_BACKOFF_LATENCY] as Long? ?: JobInfo.DEFAULT_INITIAL_BACKOFF_MILLIS

private val Context.jobScheduler get() = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

private val Context.appWidgetManager get() = AppWidgetManager.getInstance(this)

private fun Context.intentLaunchMainActivity(): PendingIntent {
    val launchIntent = with(Intent(ACTION_MAIN)) {
        addCategory(Intent.CATEGORY_LAUNCHER)
        component = ComponentName(packageName, MainActivity::class.java.name)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return PendingIntent.getActivity(this, 0, launchIntent, 0)
}

private fun reportScheduleResult(task: String, resultCode: Int) {
    when (resultCode) {
        JobScheduler.RESULT_SUCCESS -> {
            info(CC_PRIVATE) { "Scheduled to $task" }
        }
        JobScheduler.RESULT_FAILURE -> {
            severe(CC_PRIVATE) { "Failed to schedule to $task" }
        }
        else -> {
            severe(CC_PRIVATE) { "Unknown scheduler result code $resultCode" }
        }
    }
}

/**
 * We use Last-Modified modulo one hour due to DHMZ's broken Last-Modified
 * reporting (it's off by one hour)
 *
 * @param lastModified last modified time in seconds past full hour
 */
private fun millisToNextUpdate(lastModified : Long, updateIntervalMinutes: Long) : Long {
    require(updateIntervalMinutes in 0 until 60) { "updateInterval out of range: $updateIntervalMinutes" }
    require(lastModified in 0 until SECS_IN_HOUR) { "lastModified out of range: $lastModified" }

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
