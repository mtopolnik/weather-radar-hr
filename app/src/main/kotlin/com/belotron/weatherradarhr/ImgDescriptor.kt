package com.belotron.weatherradarhr

import com.belotron.weatherradarhr.gifdecode.Pixels

private const val ANIMATION_COVERS_MINUTES = 95

val imgDescs = arrayOf(
        ImgDescriptor(0, "HR", "http://vrijeme.hr/kompozit-anim.gif", 15,
                kradarShape, KradarOcr::ocrKradarTimestamp),
        ImgDescriptor(1, "SLO", "http://meteo.arso.gov.si/uploads/probase/www/observ/radar/si0-rm-anim.gif", 5,
                lradarShape, LradarOcr::ocrLradarTimestamp)
)

class ImgDescriptor(
        val index: Int,
        val title: String,
        val url: String,
        val minutesPerFrame: Int,
        val mapShape: MapShape,
        val ocrTimestamp: (Pixels) -> Long
) {
    val framesToKeep = Math.ceil(ANIMATION_COVERS_MINUTES.toDouble() / minutesPerFrame).toInt()
    val filename = url.substringAfterLast('/')
}
