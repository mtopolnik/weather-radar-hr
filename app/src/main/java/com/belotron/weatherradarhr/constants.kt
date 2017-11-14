package com.belotron.weatherradarhr

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import com.loopj.android.http.AsyncHttpClient

const val LOGTAG = "WeatherRadar"
const val LOOP_COUNT = 50
const val ANIMATION_DURATION = 250
const val BUFSIZ = 512
const val ANIMATION_COVERS_MINUTES = 100
const val APPWIDGET_IMG_URL = "http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar.gif"

val client : AsyncHttpClient = AsyncHttpClient()

val images = arrayOf(
        ImgDescriptor("Lisca",
                "http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar_anim.gif",
                10)
        ,
        ImgDescriptor("Puntijarka-Bilogora-Osijek",
                "http://vrijeme.hr/kradar-anim.gif",
                15)
//                ,
//                ImgDescriptor("Dubrovnik",
//                        "http://vrijeme.hr/dradar-anim.gif",
//                        15)
)

class ImgDescriptor(val title: String, val url: String, val minutesPerFrame: Int) {
    val framesToKeep = Math.ceil(ANIMATION_COVERS_MINUTES.toDouble() / minutesPerFrame).toInt()

    fun filename(): String {
        return url.substring(url.lastIndexOf('/') + 1, url.length)
    }
}


fun updateRemoteViews(context : Context, bytes: ByteArray?) {
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
