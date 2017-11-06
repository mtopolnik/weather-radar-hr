package com.belotron.weatherradarhr

import android.app.job.JobParameters
import android.app.job.JobService
import android.graphics.Bitmap
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

class UpdateWidgetService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        MyLog.i("UpdateWidgetService start job")
        UpdateWidgetImage.updateWidget(applicationContext)
                .listener(UpdateWidget(params))
                .submit()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        MyLog.i("UpdateWidgetService stop job")
        return true;
    }

    private inner class UpdateWidget(val params: JobParameters?): RequestListener<Bitmap> {
        override fun onResourceReady(bitmap: Bitmap?, model: Any?, target: Target<Bitmap>?,
                                     dataSource: DataSource?, isFirstResource: Boolean): Boolean
        {
            MyLog.i("Job Finished")
            jobFinished(params, false)
            return false
        }

        override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Bitmap>?,
                                  isFirstResource: Boolean): Boolean
        {
            MyLog.i("Job Finished with Failure")
            jobFinished(params, true)
            return false
        }
    }
}
