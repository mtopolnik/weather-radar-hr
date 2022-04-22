package com.belotron.weatherradarhr

import android.content.Context
import com.belotron.weatherradarhr.FetchPolicy.*
import com.belotron.weatherradarhr.gifdecode.BitmapFreelists
import com.belotron.weatherradarhr.gifdecode.GifFrame
import com.belotron.weatherradarhr.gifdecode.GifSequence
import com.belotron.weatherradarhr.gifdecode.ImgDecodeException
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

val frameSequenceLoaders = arrayOf(KradarSequenceLoader(), LradarSequenceLoader())

const val MILLIS_IN_MINUTE = 60_000

sealed class FrameSequenceLoader(
        val positionInUI: Int,
        val title: String,
        val minutesPerFrame: Int,
        val mapShape: MapShape,
) {
    fun correctFrameCount(animationCoversMinutes: Int): Int =
            ceil(animationCoversMinutes.toDouble() / minutesPerFrame).toInt()

    // Returns Pair(isOffline, sequence)
    abstract suspend fun fetchFrameSequence(
            context: Context,
            animationCoversMinutes: Int,
            fetchPolicy: FetchPolicy
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
    private val lowestIndexAtServer = 1
    private val highestIndexAtServer = 25

    override suspend fun fetchFrameSequence(
            context: Context, animationCoversMinutes: Int, fetchPolicy: FetchPolicy
    ): Pair<Boolean, PngSequence?> {
        // This function is not designed to work correctly for these two fetch policies:
        assert(fetchPolicy != ONLY_CACHED) { "fetchPolicy == ONLY_CACHED" }
        assert(fetchPolicy != ONLY_IF_NEW) { "fetchPolicy == ONLY_IF_NEW" }
        val frames = mutableListOf<PngFrame>()
        val sequence = newKradarSequence(frames)
        val allocator = BitmapFreelists()
        val decoder = sequence.intoDecoder(allocator)
        val correctFrameCount = correctFrameCount(animationCoversMinutes)
        val indexOfFrameZeroAtServer = highestIndexAtServer - (correctFrameCount - 1)
        if (indexOfFrameZeroAtServer < 1) {
            throw RuntimeException("Asked for too many frames, max is 25")
        }

        val isOffline = AtomicBoolean(false)

        suspend fun fetchFrame(indexAtServer: Int, fetchPolicy: FetchPolicy): PngFrame {
            val url = urlTemplate.format(indexAtServer)
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

        fun timestampInCache(indexAtServer: Int): Long? {
            val url = urlTemplate.format(indexAtServer)
            val (lastModified, nullableFrame) = fetchPngFromCache(context, url)
            val frame = nullableFrame ?: return null
            frames.add(frame)
            decoder.assignTimestamp(frames.size - 1, KradarOcr::ocrKradarTimestamp)
            frames.removeLast()
            return frame.timestamp
        }

        var fetchPolicy = fetchPolicy
        info { "Requested fetch policy $fetchPolicy" }
        try {
            val tempIndexOfMostRecentCached = -1
            val mostRecentTimestampInCache = withContext(IO) { timestampInCache(highestIndexAtServer) }
            if (mostRecentTimestampInCache != null && fetchPolicy != PREFER_CACHED) {
                context.copyCached(urlTemplate.format(highestIndexAtServer),
                        urlTemplate.format(tempIndexOfMostRecentCached))
            }
            val mostRecentFrame = fetchFrame(highestIndexAtServer, fetchPolicy).also { frame ->
                frames.add(frame)
                withContext(Default) {
                    decoder.assignTimestamp(0, KradarOcr::ocrKradarTimestamp)
                }
                frames.clear()
            }
            val mostRecentTimestamp = mostRecentFrame.timestamp

            // Reuse cached images by renaming them to the expected new URLs
            // after DHMZ posts a new image and renames the previous images
            withContext(IO) {
                if (mostRecentTimestampInCache == null || mostRecentTimestampInCache >= mostRecentTimestamp) {
                    info { "mostRecentTimestampInCache == null || mostRecentTimestampInCache >= mostRecentTimestampAtServer" }
                    return@withContext
                }
                val diffBetweenMostRecentAtServerAndInCache = mostRecentTimestamp - mostRecentTimestampInCache
                val millisInMinute = 60_000
                val indexShift = (diffBetweenMostRecentAtServerAndInCache + millisInMinute) /
                        (minutesPerFrame * millisInMinute)
                if (indexShift < 1 || indexShift > highestIndexAtServer + 1 - lowestIndexAtServer) {
                    info { "There are no cached frames to reuse" }
                    return@withContext
                }
                synchronized (CACHE_LOCK) {
                    val sortedByTimestamp = TreeSet(compareBy(Pair<Int, Long>::second))
                    info { "Collecting cached DHMZ timestamps" }
                    run {
                        var addedCount = 0
                        for (i in (highestIndexAtServer - 1).downTo(lowestIndexAtServer)) {
                            val timestamp = timestampInCache(i) ?: continue
                            addedCount += 1
                            sortedByTimestamp.add(Pair(i, timestamp))
                        }
                        if (sortedByTimestamp.size < addedCount) {
                            info { "Cached DHMZ timestamps have duplicates" }
                            return@withContext
                        }
                    }
                    run {
                        var previousIndex = -1
                        for ((indexAtServer, _) in sortedByTimestamp) {
                            if (indexAtServer < previousIndex) {
                                info { "DHMZ frames aren't ordered by timestamp" }
                                return@withContext
                            }
                            previousIndex = indexAtServer
                        }
                    }
                    sortedByTimestamp.add(Pair(tempIndexOfMostRecentCached, mostRecentTimestampInCache))
                    for ((indexAtServer, _) in sortedByTimestamp) {
                        val indexAtServerSoFar = if (indexAtServer == tempIndexOfMostRecentCached)
                            highestIndexAtServer else indexAtServer
                        val indexAtServerToBe = indexAtServerSoFar - indexShift
                        val urlNow = urlTemplate.format(indexAtServer)
                        if (indexAtServerToBe < 1) {
                            try {
                                info { "Deleting cached DHMZ image $indexAtServer" }
                                context.deleteCached(urlNow)
                            } catch (e: IOException) {
                                warn { "Failed to delete $urlNow in cache: " + e.message ?: "<reason missing>" }
                            }
                        } else {
                            val urlToBe = urlTemplate.format(indexAtServerToBe)
                            info { "Reusing DHMZ image $indexAtServer as $indexAtServerToBe" }
                            context.renameCached(urlNow, urlToBe)
                        }
                    }
                }
                fetchPolicy = PREFER_CACHED
            }
            coroutineScope {
                indexOfFrameZeroAtServer.until(highestIndexAtServer).map { i ->
                    async {
                        var attempt = 0
                        while (true) {
                            attempt += 1
                            try {
                                return@async fetchFrame(i, fetchPolicy)
                            } catch (e: Exception) {
                                if (attempt == 5) {
                                    throw e
                                }
                                delay(1_000)
                            }
                        }
                        @Suppress("ThrowableNotThrown", "UNREACHABLE_CODE")
                        throw RuntimeException("unreachable")
                    }
                }.forEach {
                    frames.add(it.await())
                }
            }
            withContext(Default) {
                coroutineScope {
                    for (i in 0.until(frames.size)) {
                        launch { decoder.assignTimestamp(i, KradarOcr::ocrKradarTimestamp) }
                    }
                }
                val sortedFrames = TreeSet(compareBy(PngFrame::timestamp)).apply {
                    addAll(sequence.frames)
                    add(mostRecentFrame)
                }
                frames.clear()
                val oldestAcceptableTimestamp =
                        mostRecentTimestamp - (correctFrameCount - 1) * minutesPerFrame * MILLIS_IN_MINUTE
                sortedFrames.dropWhile { it.timestamp < oldestAcceptableTimestamp }
                        .forEach { frames.add(it) }
            }
        } catch (e: NullFrameException) {
            return Pair(true, null)
        } finally {
            decoder.dispose()
        }
        info { "Kradar done fetchFrameSequence, frameCount = ${sequence.frames.size}" }
        return Pair(isOffline.get(), sequence)
    }

    private fun newKradarSequence(frames: MutableList<PngFrame>) = PngSequence(frames)
}

class LradarSequenceLoader : FrameSequenceLoader(
        positionInUI = 1,
        title = "SLO",
        minutesPerFrame = 5,
        mapShape = lradarShape,
) {
    private val url = "https://meteo.arso.gov.si/uploads/probase/www/observ/radar/si0-rm-anim.gif"

    override suspend fun fetchFrameSequence(
            context: Context, correctFrameCount: Int, fetchPolicy: FetchPolicy
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
        }
        return Pair(lastModified == 0L, sequence)
    }
}
