package com.belotron.weatherradarhr

import com.belotron.weatherradarhr.gifdecode.Pixels

private const val ANIMATION_COVERS_MINUTES = 100

val imgDescs = arrayOf(
        ImgDescriptor(0, "HR", "http://vrijeme.hr/kompozit-anim.gif", 15,
                R.id.vg_kradar, R.id.text_kradar, R.id.img_kradar, kradarShape,
                R.id.progress_bar_kradar, R.id.broken_img_kradar, KradarOcr::ocrKradarTimestamp),
        ImgDescriptor(1, "SLO", "http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar_anim.gif", 10,
                R.id.vg_lradar, R.id.text_lradar, R.id.img_lradar, lradarShape,
                R.id.progress_bar_lradar, R.id.broken_img_lradar, LradarOcr::ocrLradarTimestamp)
)

class ImgDescriptor(
        val index: Int,
        val title: String,
        val url: String,
        val minutesPerFrame: Int,
        val viewGroupId: Int,
        val textViewId: Int,
        val imgViewId: Int,
        val mapShape: MapShape,
        val progressBarId: Int,
        val brokenImgViewId: Int,
        val ocrTimestamp: (Pixels) -> Long
) {
    val framesToKeep = Math.ceil(ANIMATION_COVERS_MINUTES.toDouble() / minutesPerFrame).toInt()
    val filename = url.substringAfterLast('/')
}
