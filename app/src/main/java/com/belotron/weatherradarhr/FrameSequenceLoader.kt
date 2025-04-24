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


class EumetsatSequenceLoader : FrameSequenceLoader(
    // Hack: reports 2 mins per frame where it's actually 15 mins.
    // This speeds it up 7.5x and covers 7.5x more time, appropriate for a satellite animation.
    "https://eumetview.eumetsat.int/static-images/MSG/IMAGERY/IR108/BW/CENTRALEUROPE", 2, { 0 }
) {
    private val FIFTEEN_MINS = TimeUnit.MINUTES.toMillis(15)
    private val imgTsRegex = """(?<=<option value="\d\d?\d?">)[^<]+""".toRegex()
    private val imgIdRegex = """(?<=array_nom_imagen\[\d\d?\d?]=")[^"]+""".toRegex()
    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yy   HH:mm 'UTC'")

    override fun incrementallyFetchFrameSequence(
        context: Context,
        animationCoversMinutes: Int,
        fetchPolicy: FetchPolicy
    ): Flow<StdSequence> = flow {

        suspend fun fetchFrameBytes(imgId: String): ByteArray {
            val (_, imgBytes) = fetchBytes(context, "$url/IMAGESDisplay/$imgId", PREFER_CACHED)
            return imgBytes ?: ByteArray(0)
        }

        if (fetchPolicy == ONLY_IF_NEW || fetchPolicy == ONLY_CACHED) {
            throw IllegalArgumentException("This function supports only UP_TO_DATE and PREFER_CACHED fetch policies")
        }
        val targetFrameCount = correctFrameCount(animationCoversMinutes)
        val (_, htmlMaybe) = fetchString(context, "$url/index.htm", fetchPolicy)
        val html = htmlMaybe ?: return@flow
        val imgIds = imgIdRegex.findAll(html).map { it.value }.toList()
        val imgTimestamps = imgTsRegex.findAll(html).map { LocalDateTime.from(dateFormat.parse(it.value)) }.toList()
        val dlLists = imgTimestamps.zip(imgIds).let { rawPairs ->
            if (fetchPolicy == PREFER_CACHED) {
                listOf(rawPairs.filter { it.first.minute == 0 })
            } else {
                listOf(
                    rawPairs.filter { it.first.minute == 0 },
                    rawPairs.filter { it.first.minute % 30 == 0 },
                    rawPairs,
                )
            }.map { list -> list.map { it.first.atZone(ZoneOffset.UTC).toInstant().toEpochMilli() to it.second } }
        }
        for (dlList in dlLists) {
            val frames = mutableListOf(StdFrame(fetchFrameBytes(dlList[0].second), dlList[0].first))
            var frameCount = 1
            forLoop@ for (i in 1 ..< dlList.size) {
                val frameBytes = fetchFrameBytes(dlList[i].second)
                val earlierTs = dlList[i].first
                var laterTs = dlList[i - 1].first
                var interpolationCount = 0
                while (laterTs > earlierTs && interpolationCount < 12) {
                    if (frameCount == targetFrameCount) {
                        break@forLoop
                    }
                    interpolationCount += 1
                    frameCount += 1
                    laterTs -= FIFTEEN_MINS
                    frames += StdFrame(frameBytes, laterTs)
                }
            }
            frames.reverse()
            emit(StdSequence(frames))
        }
    }
}
