package com.belotron.weatherradarhr

import android.content.Context
import com.belotron.weatherradarhr.gifdecode.BitmapFreelists
import com.belotron.weatherradarhr.gifdecode.GifFrame
import com.belotron.weatherradarhr.gifdecode.GifSequence
import com.belotron.weatherradarhr.gifdecode.ImgDecodeException
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.withContext
import java.util.*
import kotlin.math.ceil
import kotlin.math.max

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
    private val urlTemplate = "https://vrijeme.hr/radari/anim_kompozit%d.png"

    override suspend fun fetchFrameSequence(
        context: Context, fetchPolicy: FetchPolicy
    ): Pair<Long, FrameSequence<PngFrame>?> {
        val frames = mutableListOf<PngFrame>()
        val sequence = newKradarSequence(frames)
        val allocator = BitmapFreelists()
        val decoder = sequence.intoDecoder(allocator)
        val indexOfFirstFrameAtServer = 26 - framesToKeep
        if (indexOfFirstFrameAtServer < 1) {
            throw RuntimeException("Asked to keep too many frames, max is 25")
        }
        var overallLastModified = 0L
        try {
            for (i in 0.until(framesToKeep)) {
                val url = urlTemplate.format(indexOfFirstFrameAtServer + i)
                info { "Kradar fetch $url" }
                val result = fetchPngFrame(
                    context,
                    url,
                    fetchPolicy
                )
                val (lastModified, frame) = result
                if (frame == null) {
                    return Pair(0L, null)
                }
                overallLastModified = max(lastModified, overallLastModified)
                frames.add(frame)
            }
            withContext(Default) {
                for (i in 0.until(framesToKeep)) {
                    decoder.assignTimestamp(i, KradarOcr::ocrKradarTimestamp)
                }
            }
        } finally {
            decoder.dispose()
        }
        info { "Kradar done fetchFrameSequence, frameCount = ${sequence.frames.size}" }
        return Pair(overallLastModified, sequence)
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
            withContext(Default) {
                (0 until frames.size).forEach { frameIndex ->
                    decoder.assignTimestamp(frameIndex, LradarOcr::ocrLradarTimestamp)
                }
            }
        } catch (e: ImgDecodeException) {
            severe(CcOption.CC_PRIVATE) { "Animated GIF decoding error" }
            context.invalidateCache(url)
            throw e
        } finally {
            decoder.dispose()
        }
        // SLO animated gif has repeated frames at the end, remove the duplicates
        val sortedFrames = TreeSet(compareBy(GifFrame::timestamp)).apply {
            addAll(sequence.frames)
        }
        sequence.frames.apply {
            clear()
            addAll(sortedFrames)
            while (size > framesToKeep) {
                removeAt(0)
            }
        }
        return result
    }
}
