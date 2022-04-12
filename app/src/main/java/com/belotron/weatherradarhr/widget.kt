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
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.PersistableBundle
import android.text.format.DateUtils.*
import android.widget.RemoteViews
import com.belotron.weatherradarhr.CcOption.CC_PRIVATE
import com.belotron.weatherradarhr.FetchPolicy.ONLY_IF_NEW
import com.belotron.weatherradarhr.FetchPolicy.UP_TO_DATE
import com.belotron.weatherradarhr.KradarOcr.ocrKradarTimestamp
import com.belotron.weatherradarhr.LradarOcr.ocrLradarTimestamp
import com.belotron.weatherradarhr.gifdecode.ImgDecodeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

private const val SECS_IN_HOUR = 3600L
private const val SECS_IN_MINUTE = 60L
private const val MINUTES_IN_HOUR = 60L
private const val UPDATE_AGE_PERIOD_MINUTES = 3
private const val RETRY_PERIOD_MINUTES = 10L
private const val REFRESH_IMAGE_JOB_ID_BASE = 700713272
private const val UPDATE_AGE_JOB_ID_BASE = 700723272
private const val LRADAR_CROP_X_LEFT = 10
private const val LRADAR_CROP_Y_TOP = 49
private const val LRADAR_CROP_WIDTH = 800
private const val LRADAR_CROP_HEIGHT = 600
private const val KRADAR_CROP_Y_HEIGHT = 719
private const val EXTRA_WIDGET_DESC_INDEX = "widgetDescIndex"

private val widgetDescriptors = arrayOf(
        WidgetDescriptor("LRadar", "https://meteo.arso.gov.si/uploads/probase/www/observ/radar/si0-rm.gif", 5,
                LradarWidgetProvider::class.java,
                R.drawable.lradar_widget_preview,
                lradarShape,
                cropLeft = 0,
                cropTop = LRADAR_CROP_Y_TOP,
                toTimestampedBitmap = { bitmap, isOffline ->
                    TimestampedBitmap(
                            ocrLradarTimestamp(bitmap.asPixels()),
                            isOffline,
                            bitmap.crop(LRADAR_CROP_X_LEFT, LRADAR_CROP_Y_TOP,
                                    LRADAR_CROP_WIDTH, LRADAR_CROP_HEIGHT)
            )}),
        WidgetDescriptor("KRadar", "https://vrijeme.hr/kompozit-stat.png", 10,
                KradarWidgetProvider::class.java,
                R.drawable.kradar_widget_preview,
                kradarShape,
                cropLeft = 0,
                cropTop = 0,
                toTimestampedBitmap = { bitmap, isOffline ->
                    TimestampedBitmap(
                            ocrKradarTimestamp(bitmap.asPixels()),
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
        val mapShape: MapShape,
        val cropLeft: Int,
        val cropTop: Int,
        val toTimestampedBitmap: (Bitmap, Boolean) -> TimestampedBitmap
) {
    var refreshJobRunning = false
    val imgFilename get() = url.substringAfterLast('/')
    val timestampFilename get() = "$imgFilename.timestamp"
    val index get() = widgetDescriptors.indexOf(this)
    val refreshImageJobId get() = REFRESH_IMAGE_JOB_ID_BASE + index
    val updateAgeJobId get() = UPDATE_AGE_JOB_ID_BASE + index
    val toExtras get() = PersistableBundle().apply {
        putInt(EXTRA_WIDGET_DESC_INDEX, index)
    }
}

private class TimestampedBitmap(val timestamp: Long, val isOffline: Boolean, val bitmap: Bitmap)

fun refreshWidgetsInForeground() {
    onEachWidget {
        val logHead = "refreshWidgetsInForeground ${wDesc.name}"
        info(CC_PRIVATE) { logHead }
        appCoroScope.launch {
            fetchImageAndUpdateWidget(callingFromBg = false, onlyIfNew = false).also { lastModified_mmss ->
                logFetchResult(logHead, lastModified_mmss)
            }
        }
        ensureWidgetRefreshScheduled()
    }
}

fun redrawWidgetsInForeground() {
    onEachWidget {
        if (isWidgetInUse) {
            updateRemoteViews(readImgAndTimestamp())
        }
    }
}

fun anyWidgetInUse(): Boolean {
    return widgetDescriptors
            .map { WidgetContext(appContext, it) }
            .any { it.isWidgetInUse }
}

private fun onEachWidget(action: WidgetContext.() -> Unit) {
    widgetDescriptors
            .map { WidgetContext(appContext, it) }
            .filter { it.isWidgetInUse && !it.wDesc.refreshJobRunning }
            .forEach { it.action() }
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
        val logHead = "RefreshImage $widgetName"
        info(CC_PRIVATE) { "$logHead: start job" }
        try {
            val wDesc = params.widgetDescriptor!!.apply { refreshJobRunning = true }
            val wCtx = WidgetContext(applicationContext, wDesc)
            return if (wCtx.isWidgetInUse) {
                appCoroScope.launch {
                    try {
                        val lastModified_mmss = wCtx.fetchImageAndUpdateWidget(callingFromBg = true, onlyIfNew = true)
                        jobFinished(params, lastModified_mmss == null)
                        logFetchResult(logHead, lastModified_mmss)
                        if (lastModified_mmss != null) {
                            wCtx.scheduleWidgetUpdate(millisToNextUpdate(lastModified_mmss, wDesc.updatePeriodMinutes))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        severe(CC_PRIVATE, e) { "$logHead: error in coroutine" }
                    } finally {
                        wDesc.refreshJobRunning = false
                    }
                }
                true
            } else {
                wCtx.cancelUpdateAge()
                false
            }
        } catch (e: Throwable) {
            params.widgetDescriptor?.apply { refreshJobRunning = false }
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
            with(WidgetContext(applicationContext, params.widgetDescriptor!!)) {
                if (isWidgetInUse) {
                    updateRemoteViews(readImgAndTimestamp())
                    ensureWidgetRefreshScheduled()
                } else {
                    cancelUpdateAge()
                }
            }
        } catch (e: Throwable) {
            severe(CC_PRIVATE, e) { "$logHead: error" }
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
            updateRemoteViews(null)
            scheduleUpdateAge()
            appCoroScope.launch {
                context.refreshLocation(callingFromBg = true)
                try {
                    context.receiveLocationUpdatesBg()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    severe(CC_PRIVATE, t) { "$logHead: error setting up to receive location updates" }
                }
                try {
                    val lastModified = fetchImageAndUpdateWidget(callingFromBg = true, onlyIfNew = false)
                    if (lastModified != null) {
                        info(CC_PRIVATE) { "$logHead: success" }
                        scheduleWidgetUpdate(millisToNextUpdate(lastModified, wDesc.updatePeriodMinutes))
                    } else {
                        info(CC_PRIVATE) { "$logHead: failed, scheduling to retry" }
                        scheduleWidgetUpdate(JobInfo.DEFAULT_INITIAL_BACKOFF_MILLIS)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    severe(CC_PRIVATE, t) { "$logHead: error in coroutine" }
                }
            }
        } catch (t: Throwable) {
            severe(CC_PRIVATE, t) { "$logHead: error on main thread" }
        }
    }

    // Returns the Last-Modified timestamp's mm:ss part in seconds
    suspend fun fetchImageAndUpdateWidget(callingFromBg: Boolean, onlyIfNew: Boolean): Long? {
        try {
            try {
                val (lastModified_mmss, bitmap) =
                        context.fetchBitmap(wDesc.url, if (onlyIfNew) ONLY_IF_NEW else UP_TO_DATE)
                if (bitmap == null) {
                    // This may happen only with `onlyIfNew == true`
                    return null
                }
                val tsBitmap = wDesc.toTimestampedBitmap(bitmap, false)
                info(CC_PRIVATE) { "${wDesc.name} scan started at ${context.timeFormat.format(tsBitmap.timestamp)}" }
                writeImgAndTimestamp(tsBitmap)
                context.refreshLocation(callingFromBg)
                updateRemoteViews(tsBitmap)
                return lastModified_mmss
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

    fun ensureWidgetRefreshScheduled() {
        if (!wDesc.refreshJobRunning
                && appContext.jobScheduler.allPendingJobs.none { it.id == wDesc.refreshImageJobId }
        ) {
            info(CC_PRIVATE) { "${wDesc.name}: refresh job neither scheduled nor running" }
            scheduleWidgetUpdate(JobInfo.DEFAULT_INITIAL_BACKOFF_MILLIS)
        }
    }

    fun updateRemoteViews(tsBitmap: TimestampedBitmap?) {
        val remoteViews = RemoteViews(context.packageName, R.layout.app_widget)
        with(remoteViews) {
            setOnClickPendingIntent(R.id.img_view_widget, context.intentLaunchMainActivity())
            tsBitmap?.also {
                it.bitmap.drawLocation(context.locationIfFresh)
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

    fun scheduleWidgetUpdate(latencyMillis: Long) {
        val jobInfo = JobInfo.Builder(wDesc.refreshImageJobId, ComponentName(context, RefreshImageService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(latencyMillis)
            .setOverrideDeadline(HOUR_IN_MILLIS)
            .setExtras(wDesc.toExtras)
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
                        .setExtras(wDesc.toExtras)
                        .setPeriodic(UPDATE_AGE_PERIOD_MINUTES * MINUTE_IN_MILLIS)
                        .build())
        reportScheduleResult("update age of ${wDesc.name} every three minutes", resultCode)
    }

    fun readImgAndTimestamp() : TimestampedBitmap? {
        val file = context.fileInCache(wDesc.timestampFilename)
        return try {
            file.dataIn().use {
                TimestampedBitmap(it.readLong(), it.readBoolean(),
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inMutable = true })!!)
            }
        } catch (e : Exception) {
            null
        }
    }

    private fun Bitmap.drawLocation(location: Triple<Double, Double, Long>?) {
        if (location == null) {
            warn(CC_PRIVATE) { "Location not present, not drawing on bitmap" }
            return
        }
        val (lat, lon, timestamp) = location
        val age = System.currentTimeMillis() - timestamp
        info(CC_PRIVATE) { "Draw location (%.3f, %.3f) aged %d minutes on bitmap".format(
                lat, lon, MILLISECONDS.toMinutes(age)) }
        val point = FloatArray(2)
        wDesc.mapShape.locationToPixel(lat, lon, point)
        point[0] -= wDesc.cropLeft.toFloat()
        point[1] -= wDesc.cropTop.toFloat()
        val (x, y) = point
        with(Canvas(this)) {
            val dotRadius = 0.015f * this.width
            drawCircle(x, y, dotRadius, Paint().apply { color = context.resources.getColor(R.color.locdot) })
            drawCircle(x, y, 0.6f * dotRadius, Paint().apply {
                color = context.resources.getColor(android.R.color.white)
                style = Paint.Style.STROKE
                strokeWidth = 0.25f * dotRadius
            })
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

private val JobParameters.widgetDescriptor: WidgetDescriptor?
    get() = runOrNull { widgetDescriptors[extras[EXTRA_WIDGET_DESC_INDEX] as Int] }

private val JobParameters.widgetName get() = widgetDescriptor?.name ?: ""

private val Context.jobScheduler get() = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

private val Context.appWidgetManager get() = AppWidgetManager.getInstance(this)

private fun Context.intentLaunchMainActivity(): PendingIntent {
    val launchIntent = with(Intent(ACTION_MAIN)) {
        addCategory(Intent.CATEGORY_LAUNCHER)
        component = ComponentName(packageName, MainActivity::class.java.name)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return PendingIntent.getActivity(this, 0, launchIntent,
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0))
}

private fun logFetchResult(logHead: String, lastModified_mmss: Long?) {
    info(CC_PRIVATE) { "$logHead: " +
            if (lastModified_mmss != null) "success, last modified ${formatElapsedTime(lastModified_mmss)}"
            else "no new image"
    }
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
 * reporting (It applies the conversion from Zagreb time to GMT twice)
 *
 * @param lastModified_mmss last modified time in seconds past full hour
 */
private fun millisToNextUpdate(lastModified_mmss : Long, updateIntervalMinutes: Long) : Long {
    require(updateIntervalMinutes in 0 until MINUTES_IN_HOUR) { "updateInterval out of range: $updateIntervalMinutes" }
    require(lastModified_mmss in 0 until SECS_IN_HOUR) { "lastModified out of range: $lastModified_mmss" }

    val now_mmss = currentTime_mmss()
    val mmss_sinceLastModified =
            if (now_mmss >= lastModified_mmss) now_mmss - lastModified_mmss
            else (now_mmss + SECS_IN_HOUR) - lastModified_mmss
    val proposedDelay = updateIntervalMinutes * SECS_IN_MINUTE - mmss_sinceLastModified
    return SECONDS.toMillis(proposedDelay.takeIf { it > 0 } ?: RETRY_PERIOD_MINUTES * SECS_IN_MINUTE)
}

private fun currentTime_mmss() : Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("GMT"))
    return SECS_IN_MINUTE * cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND)
}
