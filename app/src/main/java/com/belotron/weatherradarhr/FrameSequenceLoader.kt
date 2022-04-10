package com.belotron.weatherradarhr

import android.content.Context
import com.belotron.weatherradarhr.gifdecode.BitmapFreelists
import com.belotron.weatherradarhr.gifdecode.GifSequence
import com.belotron.weatherradarhr.gifdecode.ImgDecodeException
import kotlin.math.ceil

private const val ANIMATION_COVERS_MINUTES = 95

val frameSequenceLoaders = arrayOf(KradarSequenceLoader(), LradarSequenceLoader())

sealed class FrameSequenceLoader(
    val index: Int,
    val title: String,
    val minutesPerFrame: Int,
    val mapShape: MapShape,
) {
    val framesToKeep = ceil(ANIMATION_COVERS_MINUTES.toDouble() / minutesPerFrame).toInt()

    abstract suspend fun fetchFrameSequence(
        context: Context,
        fetchPolicy: FetchPolicy
    ): Pair<Long, FrameSequence<out Frame>?>
}

class KradarSequenceLoader : FrameSequenceLoader(
    index = 0,
    title = "HR",
    minutesPerFrame = 5,
    mapShape = kradarShape,
) {
    override suspend fun fetchFrameSequence(
        context: Context, fetchPolicy: FetchPolicy
    ): Pair<Long, FrameSequence<PngFrame>?> {
        val frames = mutableListOf<PngFrame>()
        val sequence = newKradarSequence(frames)
        val allocator = BitmapFreelists()
        val decoder = sequence.intoDecoder(allocator)
        val indexOfFirstFrameAtServer = 26 - framesToKeep
        val timestampOfFrame0 = System.currentTimeMillis() - framesToKeep * minutesPerFrame * 60_000
        try {
            for (i in 0.until(framesToKeep)) {
                val result = fetchPngFrame(
                    context,
                    "https://vrijeme.hr/radari/anim_kompozit%d.png".format(indexOfFirstFrameAtServer + i),
                    fetchPolicy
                )
                val (_, frame) = result
                if (frame == null) {
                    return Pair(0L, null)
                }
                frames.add(frame)
                decoder.assignTimestamp(i) { timestampOfFrame0 + i * minutesPerFrame * 60_000 }
//                    KradarOcr::ocrKradarTimestamp
            }
        } finally {
            decoder.dispose()
        }
        return Pair(0, newKradarSequence(mutableListOf()))
    }

    private fun newKradarSequence(frames: MutableList<PngFrame>) = PngSequence(frames, 720, 751)
}

class LradarSequenceLoader : FrameSequenceLoader(
    index = 1,
    title = "SLO",
    minutesPerFrame = 5,
    mapShape = lradarShape,
) {
    private val url = "https://meteo.arso.gov.si/uploads/probase/www/observ/radar/si0-rm-anim.gif"

    override suspend fun fetchFrameSequence(
        context: Context, fetchPolicy: FetchPolicy
    ): Pair<Long, GifSequence?> {
        val result = fetchGifSequence(context, url, fetchPolicy)
        val (_, sequence) = result
        if (sequence == null) {
            return result
        }
        val allocator = BitmapFreelists()
        val decoder = sequence.intoDecoder(allocator)
        try {
            val frames = sequence.frames
            (0 until frames.size).forEach { frameIndex ->
                decoder.assignTimestamp(frameIndex, LradarOcr::ocrLradarTimestamp)
            }
        } catch (e: ImgDecodeException) {
            severe(CcOption.CC_PRIVATE) { "Animated GIF decoding error" }
            context.invalidateCache(url)
            throw e
        } finally {
            decoder.dispose()
        }
        return result
    }
}
