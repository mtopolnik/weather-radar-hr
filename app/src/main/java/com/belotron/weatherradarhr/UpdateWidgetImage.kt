package com.belotron.weatherradarhr

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.RemoteViews
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy.NONE
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target

object UpdateWidgetImage {
    fun updateWidget(context : Context): RequestBuilder<Bitmap> {
        return Glide.with(context)
                .asBitmap()
                .load("http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar.gif")
                .apply(RequestOptions().skipMemoryCache(true).diskCacheStrategy(NONE))
                .listener(UpdateWidget(context))
    }
}

private class UpdateWidget(val context: Context): RequestListener<Bitmap> {
    override fun onResourceReady(bitmap: Bitmap?, model: Any?, target: Target<Bitmap>?,
                                 dataSource: DataSource?, isFirstResource: Boolean): Boolean
    {
        val remoteViews = RemoteViews(context.packageName, R.layout.app_widget)
        remoteViews.setOnClickPendingIntent(R.id.img_view_widget, onClickIntent(context))
        remoteViews.setImageViewBitmap(R.id.img_view_widget, bitmap)
        AppWidgetManager.getInstance(context)
                .updateAppWidget(ComponentName(context, MyWidgetProvider::class.java), remoteViews)
        MyLog.i("Updated App Widget")
        return true
    }

    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Bitmap>?,
                              isFirstResource: Boolean): Boolean
    {
        MyLog.i("RequestListener: Load Failed")
        return true
    }

    private fun onClickIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        intent.addCategory("android.intent.category.LAUNCHER")
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        intent.component = ComponentName(context.packageName, MainActivity::class.java.name)
        return PendingIntent.getActivity(context, 0, intent, 0)
    }
}
