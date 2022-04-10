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
    val correctFrameCount = ceil(ANIMATION_COVERS_MINUTES.toDouble() / minutesPerFrame).toInt()

    abstract suspend fun fetchFrameSequence(
        context: Context,
        fetchPolicy: FetchPolicy
    // Pair(isOffline, sequence)
    ): Pair<Boolean, FrameSequence<out Frame>?>
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
    ): Pair<Boolean, PngSequence?> {
        val frames = mutableListOf<PngFrame>()
        val sequence = newKradarSequence(frames)
        val allocator = BitmapFreelists()
        val decoder = sequence.intoDecoder(allocator)
        val indexOfFirstFrameAtServer = 26 - correctFrameCount
        if (indexOfFirstFrameAtServer < 1) {
            throw RuntimeException("Asked for too many frames, max is 25")
        }
        var isOffline = false
        try {
            for (i in 0.until(correctFrameCount)) {
                val url = urlTemplate.format(indexOfFirstFrameAtServer + i)
                info { "Kradar fetch $url" }
                val (lastModified, frame) = try {
                    fetchPngFrame(context, url, fetchPolicy)
                } catch (e: ImageFetchException) {
                    isOffline = true
                    Pair(0L, e.cached as PngFrame?)
                }
                if (frame == null) {
                    return Pair(true, null)
                }
                frames.add(frame)
            }
            withContext(Default) {
                for (i in 0.until(correctFrameCount)) {
                    decoder.assignTimestamp(i, KradarOcr::ocrKradarTimestamp)
                }
            }
        } finally {
            decoder.dispose()
        }
        info { "Kradar done fetchFrameSequence, frameCount = ${sequence.frames.size}" }
        return Pair(isOffline, sequence)
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
    ): Pair<Boolean, GifSequence?> {
        val (lastModified, sequence) = try {
            fetchGifSequence(context, url, fetchPolicy)
        } catch (e: ImageFetchException) {
            Pair(0L, e.cached as GifSequence?)
        }
        if (sequence == null) {
            return Pair(true, null)
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
            while (size > correctFrameCount) {
                removeAt(0)
            }
        }
        return Pair(lastModified == 0L, sequence)
    }
}
