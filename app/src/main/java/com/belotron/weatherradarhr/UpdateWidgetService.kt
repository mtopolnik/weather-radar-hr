package com.belotron.weatherradarhr

import android.app.job.JobParameters
import android.app.job.JobService
import com.belotron.weatherradarhr.ImageRequest.sendImageRequest

class UpdateWidgetService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        MyLog.i("UpdateWidgetService start job")
        sendImageRequest(applicationContext, APPWIDGET_IMG_URL,
                onSuccess = { updateRemoteViews(applicationContext, it) },
                onCompletion = { jobFinished(params, false) })
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        MyLog.i("UpdateWidgetService stop job")
        return true;
    }
}
