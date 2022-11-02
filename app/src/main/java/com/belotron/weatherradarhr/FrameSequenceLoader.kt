package com.belotron.weatherradarhr

import android.content.Context
import com.belotron.weatherradarhr.CcOption.CC_PRIVATE
import com.belotron.weatherradarhr.FetchPolicy.*
import com.belotron.weatherradarhr.Outcome.*
import com.belotron.weatherradarhr.gifdecode.BitmapFreelists
import com.belotron.weatherradarhr.gifdecode.GifFrame
import com.belotron.weatherradarhr.gifdecode.GifSequence
import com.belotron.weatherradarhr.gifdecode.ImgDecodeException
import com.belotron.weatherradarhr.gifdecode.Pixels
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

val frameSequenceLoaders = arrayOf(KradarSequenceLoader(), LradarSequenceLoader())

const val MILLIS_IN_MINUTE = 60_000
private const val RETRY_TIME_BUDGET_MILLIS = 90_000L
private const val FRAME_RETRY_DELAY_MILLIS = 1_000L
private const val SEQUENCE_RETRY_DELAY_MILLIS = 2_000L
private const val INITIAL_EMIT_DELAY_MILLIS = 500L
private const val EMIT_INTERVAL_MILLIS = 2_000L

enum class Outcome {
    SUCCESS, PARTIAL_SUCCESS, FAILURE
}

sealed class FrameSequenceLoader(
    val positionInUI: Int,
    val title: String,
    val minutesPerFrame: Int,
    val mapShape: MapShape,
    val ocrTimestamp: (Pixels) -> Long
) {
    fun correctFrameCount(animationCoversMinutes: Int): Int =
        ceil(animationCoversMinutes.toDouble() / minutesPerFrame).toInt() + 1

    // Returns Pair(isOffline, sequence)
    abstract fun incrementallyFetchFrameSequence(
        context: Context,
        animationCoversMinutes: Int,
        fetchPolicy: FetchPolicy
    ): Flow<FrameSequence<out Frame>?>
}

class NullFrameException : Exception("Failed to fetch, missing from cache")

private fun withGapsFilledIn(frames: Array<PngFrame?>, mostRecentFrame: PngFrame): List<PngFrame> =
    frames.mapIndexed { i, frame ->
        frame
        ?: (i - 1).downTo(0).map { frames[it] }.find { it != null }
        ?: (i + 1).until(frames.size).map { frames[it] }.find { it != null }
        ?: mostRecentFrame
    }

private fun frameTimestampsString(frames: List<Frame?>): String {
    val mostRecentTimestamp = (frames.last() ?: return "xxx").timestamp
    val b = StringBuilder()
    var afterFirst = false
    for (frame in frames) {
        if (afterFirst) {
            b.append(" ")
        }
        if (frame == null) {
            b.append("..")
        } else {
            val lagMillis = mostRecentTimestamp - frame.timestamp
            b.append("%2d".format(lagMillis / (5 * MILLIS_IN_MINUTE)))
        }
        afterFirst = true
    }
    return b.toString()
}

class KradarSequenceLoader : FrameSequenceLoader(
    positionInUI = 0,
    title = "HR",
    minutesPerFrame = 5,
    mapShape = kradarShape,
    ocrTimestamp = KradarOcr::ocrKradarTimestamp
) {
    private val urlTemplate = "https://vrijeme.hr/radari/anim_kompozit%d.png"
    private val lowestIndexAtServer = 1
    private val highestIndexAtServer = 25

    override fun incrementallyFetchFrameSequence(
        context: Context, animationCoversMinutes: Int, fetchPolicy: FetchPolicy
    ): Flow<PngSequence?> {
        // This function is not designed to work correctly for these two fetch policies:
        assert(fetchPolicy != ONLY_CACHED) { "fetchPolicy == ONLY_CACHED" }
        assert(fetchPolicy != ONLY_IF_NEW) { "fetchPolicy == ONLY_IF_NEW" }
        val correctFrameCount = correctFrameCount(animationCoversMinutes)
        val indexOfFrameZeroAtServer = highestIndexAtServer - (correctFrameCount - 1)
        if (indexOfFrameZeroAtServer < 1) {
            throw RuntimeException("Asked for too many frames, max is 25")
        }

        suspend fun invalidateInCache(url: String) {
            withContext(IO) {
                context.invalidateCache(url)
            }
        }

        suspend fun invalidateAllInCache() {
            withContext(IO) {
                synchronized(CACHE_LOCK) {
                    for (index in indexOfFrameZeroAtServer until highestIndexAtServer) {
                        context.invalidateCache(urlTemplate.format(index))
                    }
                }
            }
        }

        return flow {
            while (true) {
                val allocator = BitmapFreelists()

                suspend fun fetchFrame(indexAtServer: Int, fetchPolicy: FetchPolicy): Pair<Outcome, PngFrame?> {
                    val url = urlTemplate.format(indexAtServer)
                    val startTime = System.currentTimeMillis()
                    while (true) {
                        info { "Kradar fetch $url with $fetchPolicy" }
                        var outcome = SUCCESS
                        try {
                            val frame = try {
                                val nullableFrame = fetchPngFrame(context, url, fetchPolicy)
                                val frame = nullableFrame ?: throw NullFrameException()
                                try {
                                    withContext(Default) {
                                        val bitmap = decodeFrame(frame, allocator)
                                        try {
                                            if (bitmap.getPixel(360, 670) == 0) {
                                                throw NullFrameException()
                                            }
                                            frame.timestamp = ocrTimestamp(bitmap, ocrTimestamp)
                                            if (bitmap.getPixel(360, 748) == 0) {
                                                outcome = PARTIAL_SUCCESS
                                                invalidateInCache(url)
                                            }
                                        } finally {
                                            allocator.release(bitmap)
                                        }
                                    }
                                } catch (e: Exception) {
                                    severe(CC_PRIVATE) { "Error decoding/OCRing PNG: ${e.message}" }
                                    invalidateInCache(url)
                                    throw e
                                }
                                frame
                            } catch (e: ImageFetchException) {
                                val cached = e.cached ?: throw NullFrameException()
                                val frame = cached as PngFrame
                                outcome = PARTIAL_SUCCESS
                                frame
                            }
                            return Pair(outcome, frame)
                        } catch (e: Exception) {
                            val timeSpent = System.currentTimeMillis() - startTime
                            if (timeSpent > RETRY_TIME_BUDGET_MILLIS) {
                                info(CC_PRIVATE) { "Spent ${timeSpent / 1000} seconds on $url without success, give up" }
                                return Pair(FAILURE, null)
                            }
                            info(CC_PRIVATE) { "Spent ${timeSpent / 1000} seconds on $url without success, retry" }
                            delay(FRAME_RETRY_DELAY_MILLIS)
                        }
                    }
                }

                suspend fun timestampInCache(indexAtServer: Int): Long? {
                    val url = urlTemplate.format(indexAtServer)
                    val nullableFrame = fetchPngFromCache(context, url)
                    return nullableFrame?.let { frame ->
                        val bitmap = decodeFrame(frame, allocator)
                        try {
                            ocrTimestamp(bitmap, ocrTimestamp)
                        } finally {
                            allocator.release(bitmap)
                        }
                    }
                }

                var havingCompleteSuccess = true
                try {
                    val tempIndexOfMostRecentCached = -1
                    val mostRecentTimestampInCache = timestampInCache(highestIndexAtServer)
                    if (mostRecentTimestampInCache != null && fetchPolicy != PREFER_CACHED) {
                        context.copyCached(
                            urlTemplate.format(highestIndexAtServer),
                            urlTemplate.format(tempIndexOfMostRecentCached)
                        )
                    }
                    val (outcomeOfMostRecent, mostRecentFrame) = fetchFrame(highestIndexAtServer, fetchPolicy)
                    val mostRecentTimestamp = (mostRecentFrame ?: throw NullFrameException()).timestamp

                    // Reuse cached images by renaming them to the expected new URLs
                    // after DHMZ posts a new image and renames the previous images
                    val canReuse = withContext(IO) {
                        if (mostRecentTimestampInCache == null) {
                            info { "mostRecentTimestampInCache == null" }
                            return@withContext false
                        }
                        val diffBetweenMostRecentAtServerAndInCache = mostRecentTimestamp - mostRecentTimestampInCache
                        if (diffBetweenMostRecentAtServerAndInCache == 0L) {
                            info { "mostRecentTimestampInCache == mostRecentTimestampAtServer" }
                            return@withContext true
                        }
                        if (diffBetweenMostRecentAtServerAndInCache < 0L) {
                            info { "mostRecentTimestampInCache > mostRecentTimestampAtServer" }
                            return@withContext false
                        }
                        val millisInMinute = 60_000
                        val indexShift = (diffBetweenMostRecentAtServerAndInCache + millisInMinute) /
                                (minutesPerFrame * millisInMinute)
                        if (indexShift < 1 || indexShift > highestIndexAtServer + 1 - lowestIndexAtServer) {
                            info { "There are no cached frames to rename" }
                            return@withContext false
                        }
                        synchronized(CACHE_LOCK) {
                            val sortedByTimestamp = TreeSet(compareBy(Pair<Int, Long>::second))
                            info { "Collecting cached DHMZ timestamps" }
                            run {
                                var addedCount = 0
                                for (i in (highestIndexAtServer - 1).downTo(lowestIndexAtServer)) {
                                    val timestamp = runBlocking { timestampInCache(i) } ?: continue
                                    addedCount += 1
                                    sortedByTimestamp.add(Pair(i, timestamp))
                                }
                                if (sortedByTimestamp.size < addedCount) {
                                    info { "Cached DHMZ timestamps have duplicates" }
                                    return@withContext false
                                }
                            }
                            run {
                                var previousIndex = -1
                                for ((indexAtServer, _) in sortedByTimestamp) {
                                    if (indexAtServer < previousIndex) {
                                        info { "DHMZ frames aren't ordered by timestamp" }
                                        return@withContext false
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
                                        warn { "Failed to delete $urlNow in cache: " + (e.message ?: "<reason missing>") }
                                    }
                                } else {
                                    val urlToBe = urlTemplate.format(indexAtServerToBe)
                                    info { "Reusing DHMZ image $indexAtServer as $indexAtServerToBe" }
                                    context.renameCached(urlNow, urlToBe)
                                }
                            }
                        }
                        true
                    }
                    var fetchedCount = 0
                    var nonFailureCount = 0
                    var nonFailureCountAtLastEmit = 0
                    val rawFrames = Array<PngFrame?>(correctFrameCount) { null }
                    channelFlow {
                        val countDown = AtomicInteger(correctFrameCount - 1)
                        val heartbeatJob = launch {
                            if (countDown.get() <= 0) {
                                return@launch
                            }
                            delay(INITIAL_EMIT_DELAY_MILLIS)
                            while (true) {
                                send(Triple(-1, SUCCESS, null))
                                delay(EMIT_INTERVAL_MILLIS)
                            }
                        }
                        send(Triple(correctFrameCount - 1, outcomeOfMostRecent, mostRecentFrame))
                        for (i in (correctFrameCount - 2).downTo(0)) {
                            launch {
                                try {
                                    val (outcome, frame) = fetchFrame(
                                        i + indexOfFrameZeroAtServer,
                                        if (canReuse) PREFER_CACHED else fetchPolicy
                                    )
                                    send(Triple(i, outcome, frame))
                                } finally {
                                    if (countDown.addAndGet(-1) == 0) {
                                        heartbeatJob.cancel()
                                    }
                                }
                            }
                            // Brief pause to improve the chance of network fetches
                            // keeping the order of launching the jobs
                            delay(10)
                        }
                    }.collect {
                        val (i, outcome, frame) = it
                        if (i != -1) {
                            havingCompleteSuccess = havingCompleteSuccess && outcome == SUCCESS
                            fetchedCount++
                            if (outcome != FAILURE) {
                                nonFailureCount++
                            }
                            rawFrames[i] = frame
                            if (fetchedCount < correctFrameCount) {
                                return@collect
                            }
                        } else if (nonFailureCount <= nonFailureCountAtLastEmit) {
                            return@collect
                        }
                        val frames = withContext(Default) {
                            val frames = withGapsFilledIn(rawFrames, mostRecentFrame).toMutableList()
                            val dstTransition = dstTransitionStatus(mostRecentTimestamp)
                            if (dstTransition == DstTransition.SUMMER_TO_WINTER) {
                                return@withContext frames
                            }
                            frames.sortWith(compareBy(PngFrame::timestamp))
                            val oldestAcceptableTimestamp =
                                mostRecentTimestamp - (correctFrameCount - 1) * minutesPerFrame * MILLIS_IN_MINUTE -
                                        if (dstTransition == DstTransition.WINTER_TO_SUMMER) 60 * MILLIS_IN_MINUTE else 0
                            val iter = frames.iterator()
                            while (iter.hasNext()) {
                                if (iter.next().timestamp < oldestAcceptableTimestamp) {
                                    iter.remove()
                                }
                            }
                            frames
                        }
                        info(CC_PRIVATE) {
                            "Raw frames  ${frameTimestampsString(rawFrames.toList())}\n" +
                            "Emit frames ${frameTimestampsString(frames)}"
                        }
                        nonFailureCountAtLastEmit = nonFailureCount
                        emit(PngSequence(frames))
                    }
                } catch (e: NullFrameException) {
                    havingCompleteSuccess = false
                } finally {
                    allocator.dispose()
                }
                if (havingCompleteSuccess) {
                    break
                }
                delay(SEQUENCE_RETRY_DELAY_MILLIS)
            }
        }
    }
}

private val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)

private fun dstTransitionStatus(timestamp: Long): DstTransition {
    val tz = TimeZone.getTimeZone("Europe/Zagreb")!!
    val cal = Calendar.getInstance(tz)
    cal.timeInMillis = timestamp
    val isInDangerZone = run {
        val compareCal = Calendar.getInstance(tz)
        compareCal.timeInMillis = timestamp
        compareCal.set(Calendar.HOUR_OF_DAY, 2)
        compareCal.set(Calendar.MINUTE, 0)
        compareCal.set(Calendar.SECOND, 0)
        compareCal.set(Calendar.MILLISECOND, 0)
        val isAfter2am = cal > compareCal
        compareCal.set(Calendar.HOUR_OF_DAY, 5)
        val isBefore5am = cal < compareCal
        println("${dateFormat.format(cal.time)} $isAfter2am $isBefore5am")
        isAfter2am && isBefore5am
    }
    if (!isInDangerZone) {
        return DstTransition.NONE
    }
    cal.set(Calendar.HOUR_OF_DAY, 0)
    val midnightIsSummerTime = cal.get(Calendar.DST_OFFSET) != 0
    cal.set(Calendar.HOUR_OF_DAY, 12)
    val noonIsSummerTime = cal.get(Calendar.DST_OFFSET) != 0
    return when (Pair(midnightIsSummerTime, noonIsSummerTime)) {
        Pair(false, false) -> DstTransition.NONE
        Pair(false, true) -> DstTransition.WINTER_TO_SUMMER
        Pair(true, true) -> DstTransition.NONE
        Pair(true, false) -> DstTransition.SUMMER_TO_WINTER
        else -> throw RuntimeException("unreachable")
    }
}

private enum class DstTransition {
    NONE,
    WINTER_TO_SUMMER,
    SUMMER_TO_WINTER,
}

class LradarSequenceLoader : FrameSequenceLoader(
    positionInUI = 1,
    title = "SLO",
    minutesPerFrame = 5,
    mapShape = lradarShape,
    ocrTimestamp = LradarOcr::ocrLradarTimestamp
) {
    private val url = "https://meteo.arso.gov.si/uploads/probase/www/observ/radar/si0-rm-anim.gif"

    override fun incrementallyFetchFrameSequence(
        context: Context, animationCoversMinutes: Int, fetchPolicy: FetchPolicy
    ): Flow<GifSequence?> = flow {

        suspend fun fetchSequence(): GifSequence? {
            while (true) {
                val (outcome, sequence) = try {
                    Pair(SUCCESS, fetchGifSequence(context, url, fetchPolicy))
                } catch (e: ImageFetchException) {
                    val cached = e.cached as GifSequence?
                    Pair(if (cached != null) PARTIAL_SUCCESS else FAILURE, cached)
                }
                if (outcome != SUCCESS) {
                    delay(SEQUENCE_RETRY_DELAY_MILLIS)
                    continue
                }
                if (sequence == null)
                    return null

                val allocator = BitmapFreelists()
                val decoder = sequence.intoDecoder(allocator, ocrTimestamp)
                try {
                    val frames = sequence.frames
                    withContext(Default) {
                        (0 until frames.size).forEach { frameIndex ->
                            decoder.assignTimestamp(frameIndex)
                        }
                    }
                } catch (e: ImgDecodeException) {
                    severe(CC_PRIVATE) { "Error decoding animated GIF: ${e.message}" }
                    withContext(IO) {
                        synchronized(CACHE_LOCK) {
                            context.invalidateCache(url)
                        }
                    }
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
                return sequence
            }
        }

        emit(fetchSequence())
    }
}
