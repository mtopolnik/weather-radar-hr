package com.belotron.weatherradarhr

import android.content.Context
import com.belotron.weatherradarhr.FetchPolicy.*
import com.belotron.weatherradarhr.gifdecode.BitmapFreelists
import com.belotron.weatherradarhr.gifdecode.GifSequence
import com.belotron.weatherradarhr.gifdecode.ImgDecodeException
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

private const val ANIMATION_COVERS_MINUTES = 95

val frameSequenceLoaders = arrayOf(KradarSequenceLoader(), LradarSequenceLoader())

sealed class FrameSequenceLoader(
        val positionInUI: Int,
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

class NullFrameException: Exception()

class KradarSequenceLoader : FrameSequenceLoader(
        positionInUI = 0,
        title = "HR",
        minutesPerFrame = 5,
        mapShape = kradarShape,
) {
    private val urlTemplate = "https://vrijeme.hr/radari/anim_kompozit%d.png"

    override suspend fun fetchFrameSequence(
        context: Context, fetchPolicy: FetchPolicy
    ): Pair<Boolean, PngSequence?> {
        // This function would not behave properly for these two fetch policies:
        assert(fetchPolicy != ONLY_CACHED) { "fetchPolicy == ONLY_CACHED" }
        assert(fetchPolicy != ONLY_IF_NEW) { "fetchPolicy == ONLY_CACHED" }
        val frames = mutableListOf<PngFrame>()
        val sequence = newKradarSequence(frames)
        val allocator = BitmapFreelists()
        val decoder = sequence.intoDecoder(allocator)
        val indexOfFirstFrameAtServer = 26 - correctFrameCount
        if (indexOfFirstFrameAtServer < 1) {
            throw RuntimeException("Asked for too many frames, max is 25")
        }

        val isOffline = AtomicBoolean(false)

        fun urlForFrameIndex(i: Int) = urlTemplate.format(indexOfFirstFrameAtServer + i)

        suspend fun fetchFrame(index: Int, fetchPolicy: FetchPolicy): PngFrame {
            val url = urlForFrameIndex(index)
            info { "Kradar fetch $url" }
            val (lastModified, frame) = try {
                fetchPngFrame(context, url, fetchPolicy)
            } catch (e: ImageFetchException) {
                isOffline.set(true)
                val cached = e.cached ?: throw IOException("Fetch error, missing from cache")
                Pair(0L, cached as PngFrame)
            }
            if (frame == null) {
                throw NullFrameException()
            }
            return frame
        }

        fun timestampInCache(index: Int): Long? {
            val url = urlForFrameIndex(index)
            val (lastModified, nullableFrame) = fetchPngFromCache(context, url)
            val frame = nullableFrame ?: return null
            frames.add(frame)
            decoder.assignTimestamp(frames.size - 1, KradarOcr::ocrKradarTimestamp)
            frames.removeLast()
            return frame.timestamp
        }

        var fetchPolicy = fetchPolicy
        try {
            val timestamp0InCache = timestampInCache(0)
            val timestamp0 = fetchFrame(0, fetchPolicy).let { frame ->
                frames.add(frame)
                decoder.assignTimestamp(0, KradarOcr::ocrKradarTimestamp)
                frame.timestamp
            }
            // Reuse cached images by renaming them to the expected new URLs
            // after DHMZ posts a new image and renames the previous images
            withContext(IO) {
                if (timestamp0InCache == null || timestamp0InCache >= timestamp0) {
                    info { "timestamp0inCache == null || timestamp0InCache >= timestamp0" }
                    return@withContext
                }
                synchronized (CACHE_LOCK) {
                    val sortedByTimestamp = TreeSet(compareBy(Pair<Int, Long>::second)).apply {
                        add(Pair(0, timestamp0InCache))
                    }
                    info { "Collecting cached DHMZ timestamps" }
                    for (i in 1.until(correctFrameCount)) {
                        val timestamp = timestampInCache(i) ?: return@withContext
                        sortedByTimestamp.add(Pair(i, timestamp))
                    }
                    if (sortedByTimestamp.size != correctFrameCount) {
                        info { "Cached DHMZ timestamps have duplicates" }
                        return@withContext
                    }
                    val urlsHaveDisorder =
                            sortedByTimestamp.mapIndexed { indexToBe, (indexNow, _) -> indexToBe != indexNow }
                            .any { it }
                    if (urlsHaveDisorder) {
                        info { "DHMZ URLs have disorder" }
                        sortedByTimestamp.forEach { (index, _) ->
                            val urlNow = urlForFrameIndex(index)
                            context.renameCached(urlNow, "$urlNow.tmp")
                        }
                        sortedByTimestamp.forEachIndexed { indexToBe, (indexNow, _) ->
                            val tmpName = "${urlForFrameIndex(indexNow)}.tmp"
                            context.renameCached(tmpName, urlForFrameIndex(indexToBe))
                        }
                    }
                    val indexOfNewTimestamp0 =
                            sortedByTimestamp.indexOfFirst { (_, timestamp) -> timestamp == timestamp0 }
                                    .takeIf { it > 0 }
                                    ?: return@withContext
                    info { "DHMZ indexOfTimestamp0 == $indexOfNewTimestamp0, " +
                            "indexOfFirstFrameAtServer == $indexOfFirstFrameAtServer" }
                    for ((index, _) in sortedByTimestamp) {
                        if (index < indexOfNewTimestamp0) {
                            try {
                                context.deleteCached(urlForFrameIndex(index))
                            } catch (e: IOException) {
                                warn { e.message ?: "<reason missing>" }
                                return@withContext
                            }
                        } else {
                            val indexToBe = index - indexOfNewTimestamp0
                            info { "Reusing DHMZ image $index as $indexToBe" }
                            context.renameCached(urlForFrameIndex(index), urlForFrameIndex(indexToBe))
                        }
                    }
                }
                fetchPolicy = PREFER_CACHED
            }
            coroutineScope {
                1.until(correctFrameCount).map { i ->
                    async(IO) { fetchFrame(i, fetchPolicy) }
                }.forEach {
                    val frame = it.await()
                    frames.add(frame)
                    decoder.assignTimestamp(frames.size - 1, KradarOcr::ocrKradarTimestamp)
                }
            }
            withContext(Default) {
                for (i in 0.until(correctFrameCount)) {
                    decoder.assignTimestamp(i, KradarOcr::ocrKradarTimestamp)
                }
                // HR images are sometimes out of order, sort them by timestamp
                val sortedFrames = TreeSet(compareBy(PngFrame::timestamp)).apply {
                    addAll(sequence.frames)
                }
                sequence.frames.apply {
                    clear()
                    addAll(sortedFrames)
                }
            }
        } catch (e: NullFrameException) {
            return Pair(true, null)
        } finally {
            decoder.dispose()
        }
        info { "Kradar done fetchFrameSequence, frameCount = ${sequence.frames.size}" }
        return Pair(isOffline.get(), sequence)
    }

    private fun newKradarSequence(frames: MutableList<PngFrame>) = PngSequence(frames, 720, 751)
}

class LradarSequenceLoader : FrameSequenceLoader(
        positionInUI = 1,
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
        sequence.frames.apply {
            while (size > correctFrameCount) {
                removeAt(0)
            }
        }
        return Pair(lastModified == 0L, sequence)
    }
}
