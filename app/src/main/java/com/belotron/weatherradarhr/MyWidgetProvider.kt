package com.belotron.weatherradarhr

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import com.belotron.weatherradarhr.ImageRequest.sendImageRequest
import java.util.concurrent.TimeUnit

const val WIDGET_REFRESH_PERIOD_MINUTES = 10L

class MyWidgetProvider : AppWidgetProvider() {

    companion object {
        val SCHEDULED_JOB_ID = 1
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        MyLog.w("onUpdate")
        sendImageRequest(context.applicationContext, APPWIDGET_IMG_URL,
                onSuccess = { updateRemoteViews(context, it) })
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        jobScheduler.schedule(JobInfo.Builder(SCHEDULED_JOB_ID,
                ComponentName(context, UpdateWidgetService::class.java))
                .setPeriodic(TimeUnit.MINUTES.toMillis(WIDGET_REFRESH_PERIOD_MINUTES))
                .build()
        )
    }
}

