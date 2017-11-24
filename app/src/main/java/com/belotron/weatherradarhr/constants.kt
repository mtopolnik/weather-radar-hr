package com.belotron.weatherradarhr

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
    val filename = url.substring(url.lastIndexOf('/') + 1, url.length)
}
