package com.belotron.weatherradarhr

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import com.belotron.weatherradarhr.UpdateWidgetImage.updateWidget
import java.util.concurrent.TimeUnit

class MyWidgetProvider : AppWidgetProvider() {

    companion object {
        val SCHEDULED_JOB_ID = 1
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        MyLog.w("onUpdate")
        updateWidget(context.applicationContext).submit()
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        jobScheduler.schedule(JobInfo.Builder(SCHEDULED_JOB_ID,
                ComponentName(context, UpdateWidgetService::class.java))
                .setPeriodic(TimeUnit.MINUTES.toMillis(10))
                .build()
        )
    }
}

