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

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.belotron.weatherradarhr.CcOption.CC_PRIVATE
import com.belotron.weatherradarhr.FetchPolicy.*
import com.belotron.weatherradarhr.Outcome.*
import com.belotron.weatherradarhr.gifdecode.BitmapFreelists
import com.belotron.weatherradarhr.gifdecode.GifFrame
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
import java.lang.IllegalArgumentException
import java.text.SimpleDateFormat
import java.util.*
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

class HrSequenceLoader(
    urlKeyword: String,
    ocrTimestamp: (Pixels) -> Long,
) : FrameSequenceLoader("https://vrijeme.hr/anim_${urlKeyword}.gif", 5, ocrTimestamp) {

    override fun incrementallyFetchFrameSequence(
        context: Context, animationCoversMinutes: Int, fetchPolicy: FetchPolicy
    ): Flow<StdSequence?> = flow {
        val gifSequence = fetchGifSequenceWithRetrying(context, fetchPolicy)
        if (gifSequence == null) {
            emit(null)
            return@flow
        }
        val pngFrames = coroutineScope {
            val pngFrameTasks = mutableListOf<Deferred<StdFrame>>()
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
                        pngFrameTasks.add(async {
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
            pngFrameTasks.map { it.await() }.toMutableList()
        }
        // Deduplicate frames, sort them by timestamp, and remove unneeded ones
        val sortedFrames = TreeSet(compareBy(StdFrame::timestamp)).apply {
            addAll(pngFrames)
        }
        val correctFrameCount = correctFrameCount(animationCoversMinutes)
        val iter = sortedFrames.iterator()
        while (sortedFrames.size > correctFrameCount && iter.hasNext()) {
            iter.next()
            iter.remove()
        }
        pngFrames.apply {
            clear()
            addAll(sortedFrames)
        }
        emit(StdSequence(pngFrames))
    }
}

class SloSequenceLoader : FrameSequenceLoader(
    "https://meteo.arso.gov.si/uploads/probase/www/observ/radar/si0-rm-anim.gif", 5, SloOcr::ocrSloTimestamp
) {
    override fun incrementallyFetchFrameSequence(
        context: Context, animationCoversMinutes: Int, fetchPolicy: FetchPolicy
    ): Flow<GifSequence?> = flow {
        val sequence = fetchGifSequenceWithRetrying(context, fetchPolicy)
        if (sequence == null) {
            emit(null)
            return@flow
        }
        val allocator = BitmapFreelists()
        try {
            val decoder = sequence.intoDecoder(allocator, ocrTimestamp)
            val frames = sequence.frames
            withContext(Default) {
                (0 until frames.size).forEach { frameIndex ->
                    decoder.assignTimestamp(frameIndex)
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
        // Deduplicate frames and sort them by timestamp
        val sortedFrames = TreeSet(compareBy(GifFrame::timestamp)).apply {
            addAll(sequence.frames)
        }
        sequence.frames.apply {
            clear()
            addAll(sortedFrames)
        }
        emit(sequence)
    }
}

class ZamgSequenceLoader : FrameSequenceLoader(
    // Hack: reports 5 mins per frame where it's actually 30 mins.
    // This speeds it up 6x and covers 6x more time, appropriate for a satellite animation.
    "https://www.zamg.ac.at/dyn/pictures/Hsatimg", 5, { 0 }
) {
    override fun incrementallyFetchFrameSequence(
        context: Context,
        animationCoversMinutes: Int,
        fetchPolicy: FetchPolicy
    ): Flow<StdSequence> = flow {
        if (fetchPolicy == ONLY_IF_NEW || fetchPolicy == ONLY_CACHED) {
            throw IllegalArgumentException("This function supports only UP_TO_DATE and PREFER_CACHED fetch policies")
        }
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val frames = mutableListOf<StdFrame>()
        val frameCount = correctFrameCount(animationCoversMinutes)

        suspend fun fetchNextFrame() {
            calendar.gotoPreviousHalfHour()
            val (_, bytes) = fetchBytes(context, calendar.toGifUrl(), PREFER_CACHED)
            frames += StdFrame(bytes!!, calendar.timeInMillis)
        }

        val maxStepsToGoBack = 12
        for (i in 1..maxStepsToGoBack) {
            try {
                fetchNextFrame()
                break
            } catch (e: ImageFetchException) {
                if (e.httpResponseCode == 404 && i < maxStepsToGoBack) {
                    continue
                }
                throw e
            }
        }
        for (i in 1..frameCount) {
            fetchNextFrame()
        }

        fun String.timeCode() = substring(length - 14, length - 4).toLong()

        val earliestFrameTimeCode = calendar.toGifUrl().timeCode()
        Log.i("zamg", "earliestFrameTimeCode $earliestFrameTimeCode")
        for (file in context.cacheDirFilesStartingWith(url)) {
            if (file.path.timeCode() < earliestFrameTimeCode) {
                Log.i("zamg", "delete stale ${file.path}")
                file.delete()
            }
        }
        frames.reverse()
        emit(StdSequence(frames))
    }

    @SuppressLint("SimpleDateFormat")
    private fun Calendar.toGifUrl(): String {
        return SimpleDateFormat("yyMMddHHmm").run {
            timeZone = TimeZone.getTimeZone("UTC")
            "$url/H${format(time)}.gif"
        }
    }

    private fun Calendar.gotoPreviousHalfHour() {
        val publishLagMinutes = 1
        // Add 30 in order to avoid negative numbers, otherwise adding 30 is neutral modulo-30
        add(Calendar.MINUTE, -((get(Calendar.MINUTE) + 30 - publishLagMinutes) % 30 + publishLagMinutes))
    }
}
