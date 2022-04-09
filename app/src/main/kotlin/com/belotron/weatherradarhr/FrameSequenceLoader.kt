package com.belotron.weatherradarhr

import android.content.Context
import com.belotron.weatherradarhr.gifdecode.GifFrame
import com.belotron.weatherradarhr.gifdecode.Pixels

private const val ANIMATION_COVERS_MINUTES = 95

val frameSequenceDescriptors = arrayOf(
    FrameSequenceDescriptor(
        0, "HR", "http://vrijeme.hr/kompozit-anim.gif", 15,
        kradarShape, KradarOcr::ocrKradarTimestamp,
        ::kradarFetchSequence
    ),
    FrameSequenceDescriptor(
        1, "SLO", "http://meteo.arso.gov.si/uploads/probase/www/observ/radar/si0-rm-anim.gif", 5,
        lradarShape, LradarOcr::ocrLradarTimestamp
    )
)

class FrameSequenceDescriptor(
    val index: Int,
    val title: String,
    val url: String,
    val minutesPerFrame: Int,
    val mapShape: MapShape,
    val ocrTimestamp: (Pixels) -> Long,
    val fetchFrameSequence: (Context, FetchPolicy) -> FrameSequence<out Frame>
) {
    val framesToKeep = Math.ceil(ANIMATION_COVERS_MINUTES.toDouble() / minutesPerFrame).toInt()
    val filename = url.substringAfterLast('/')
}

fun kradarFetchSequence(context: Context, fetchPolicy: FetchPolicy): FrameSequence<PngFrame> {
    return TODO("")
}

fun lradarFetchSequence(context: Context, fetchPolicy: FetchPolicy): FrameSequence<GifFrame> {
    return TODO("")
}
