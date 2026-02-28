/*
 * Copyright (C) 2018-2023 Marko Topolnik
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.belotron.weatherradarhr

import android.content.Context
import android.util.Log
import com.belotron.weatherradarhr.CcOption.CC_PRIVATE
import com.belotron.weatherradarhr.FetchPolicy.ONLY_CACHED
import com.belotron.weatherradarhr.FetchPolicy.ONLY_IF_NEW
import com.belotron.weatherradarhr.FetchPolicy.PREFER_CACHED
import com.belotron.weatherradarhr.Outcome.FAILURE
import com.belotron.weatherradarhr.Outcome.PARTIAL_SUCCESS
import com.belotron.weatherradarhr.Outcome.SUCCESS
import com.belotron.weatherradarhr.gifdecode.BitmapFreelists
import com.belotron.weatherradarhr.gifdecode.GifSequence
import com.belotron.weatherradarhr.gifdecode.Pixels
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.TreeSet
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

private const val SEQUENCE_RETRY_DELAY_MILLIS = 7_000L

enum class Outcome {
    SUCCESS, PARTIAL_SUCCESS, FAILURE
}

sealed class FrameSequenceLoader(
    val url: String,
    val minutesPerFrame: Int,
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

    protected suspend fun fetchGifSequenceWithRetrying(context: Context, fetchPolicy: FetchPolicy): GifSequence? {
        while (true) {
            val (outcome, gifSequence) = try {
                Pair(SUCCESS, fetchGifSequence(context, url, fetchPolicy))
            } catch (e: ImageFetchException) {
                val cached = e.cached as GifSequence?
                Pair(if (cached != null) PARTIAL_SUCCESS else FAILURE, cached)
            }
            if (outcome == SUCCESS) {
                return gifSequence
            }
            delay(SEQUENCE_RETRY_DELAY_MILLIS)
        }
    }
}

class AnimatedGifLoader(
    url: String,
    minutesPerFrame: Int,
    ocrTimestamp: (Pixels) -> Long,
) : FrameSequenceLoader(url, minutesPerFrame, ocrTimestamp) {

    override fun incrementallyFetchFrameSequence(
        context: Context, animationCoversMinutes: Int, fetchPolicy: FetchPolicy
    ): Flow<StdSequence?> = flow {
        val gifSequence = fetchGifSequenceWithRetrying(context, fetchPolicy)
        if (gifSequence == null) {
            emit(null)
            return@flow
        }
        val gifFrames = coroutineScope {
            val gifFrameTasks = mutableListOf<Deferred<StdFrame>>()
            val allocator = BitmapFreelists()
            try {
                // semaphore limits the number of simultaneous bitmaps
                val semaphore = Semaphore(Runtime.getRuntime().availableProcessors())
                val decoder = gifSequence.intoDecoder(allocator, ocrTimestamp)
                val frames = gifSequence.frames
                withContext(Default) {
                    (0 until frames.size).forEach { frameIndex ->
                        val bitmap = decoder.assignTimestampAndGetBitmap(frameIndex)
                        semaphore.acquire()
                        gifFrameTasks.add(async {
                            try {
                                StdFrame(bitmap.toCompressedBytes(), frames[frameIndex].timestamp)
                            } finally {
                                semaphore.release()
                                allocator.release(bitmap)
                            }
                        })
                    }
                }
            } catch (e: ImageDecodeException) {
                severe(CC_PRIVATE) { "Error decoding animated GIF: ${e.message}" }
                withContext(IO) {
                    context.invalidateCache(url)
                }
                throw e
            } finally {
                allocator.dispose()
            }
            gifFrameTasks.map { it.await() }.toMutableList()
        }
        // Deduplicate frames, sort them by timestamp, and remove unneeded ones
        val sortedFrames = TreeSet(compareBy(StdFrame::timestamp)).apply {
            addAll(gifFrames)
        }
        val correctFrameCount = correctFrameCount(animationCoversMinutes)
        val iter = sortedFrames.iterator()
        while (sortedFrames.size > correctFrameCount && iter.hasNext()) {
            iter.next()
            iter.remove()
        }
        gifFrames.apply {
            clear()
            addAll(sortedFrames)
        }
        emit(StdSequence(gifFrames))
    }
}

fun hrSequenceLoader(urlKeyword: String, ocrTimestamp: (Pixels) -> Long) =
    AnimatedGifLoader("https://vrijeme.hr/anim_${urlKeyword}.gif", 5, ocrTimestamp)

fun sloSequenceLoader() = AnimatedGifLoader(
    "https://meteo.arso.gov.si/uploads/probase/www/observ/radar/si0-rm-anim.gif", 5, SloOcr::ocrSloTimestamp
)

class MetNoSatelliteLoader : FrameSequenceLoader(
    // Hack: reports 2 mins per frame where it's actually 15 mins.
    // This speeds it up 7.5x and covers 7.5x more time, appropriate for a satellite animation.
    "https://api.met.no/weatherapi/geosatellite/1.4", 2, { 0 }
) {
    private val FIFTEEN_MINS = TimeUnit.MINUTES.toMillis(15)
    private val availableUrl = "$url/available?area=europe&type=infrared&size=normal"
    private val timeInUriRegex = """time=(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z)""".toRegex()
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

    override fun incrementallyFetchFrameSequence(
        context: Context,
        animationCoversMinutes: Int,
        fetchPolicy: FetchPolicy
    ): Flow<StdSequence> = flow {
        if (fetchPolicy == ONLY_IF_NEW || fetchPolicy == ONLY_CACHED) {
            throw IllegalArgumentException("This function supports only UP_TO_DATE and PREFER_CACHED fetch policies")
        }
        val (_, xmlMaybe) = fetchString(context, availableUrl, fetchPolicy)
        val xml = xmlMaybe ?: return@flow

        data class FrameSpec(val epochMillis: Long, val timeStr: String, val minute: Int)

        val allFrames = timeInUriRegex.findAll(xml)
            .map { it.groupValues[1] }
            .distinct()
            .map { ts ->
                val dt = LocalDateTime.from(dateFormat.parse(ts))
                FrameSpec(dt.atZone(ZoneOffset.UTC).toInstant().toEpochMilli(), ts, dt.minute)
            }
            .sortedByDescending { it.epochMillis }
            .toList()
        if (allFrames.isEmpty()) return@flow

        val targetFrameCount = correctFrameCount(animationCoversMinutes)
        val framesToFetch = allFrames.take(targetFrameCount)
        val dlLists = if (fetchPolicy == PREFER_CACHED) {
            listOf(framesToFetch.filter { it.minute == 0 })
        } else {
            listOf(
                framesToFetch.filter { it.minute == 0 },
                framesToFetch.filter { it.minute % 30 == 0 },
                framesToFetch,
            )
        }
        for (dlList in dlLists) {
            if (dlList.isEmpty()) continue
            val imgUrl0 = "$url/?area=europe&size=normal&type=infrared&time=${dlList[0].timeStr}"
            val (_, firstBytes) = fetchBytes(context, imgUrl0, PREFER_CACHED)
            if (firstBytes == null || firstBytes.isEmpty()) continue
            val frames = mutableListOf(StdFrame(firstBytes, dlList[0].epochMillis))
            var frameCount = 1
            forLoop@ for (i in 1 ..< dlList.size) {
                val imgUrl = "$url/?area=europe&size=normal&type=infrared&time=${dlList[i].timeStr}"
                val (_, frameBytes) = fetchBytes(context, imgUrl, PREFER_CACHED)
                if (frameBytes == null || frameBytes.isEmpty()) continue
                val earlierTs = dlList[i].epochMillis
                var laterTs = dlList[i - 1].epochMillis
                var interpolationCount = 0
                while (laterTs > earlierTs && interpolationCount < 12) {
                    if (frameCount == targetFrameCount) {
                        break@forLoop
                    }
                    interpolationCount++
                    frameCount++
                    laterTs -= FIFTEEN_MINS
                    frames += StdFrame(frameBytes, laterTs)
                }
            }
            frames.reverse()
            emit(StdSequence(frames))
        }
    }
}
