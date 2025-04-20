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
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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
    private val imgTsRegex = """(?<=<option value="\d\d?\d?">)[^<]+""".toRegex()
    private val imgIdRegex = """(?<=array_nom_imagen\[\d\d?\d?]=")[^"]+""".toRegex()
    private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yy   HH:mm 'UTC'")

    override fun incrementallyFetchFrameSequence(
        context: Context,
        animationCoversMinutes: Int,
        fetchPolicy: FetchPolicy
    ): Flow<StdSequence> = flow {
        if (fetchPolicy == ONLY_IF_NEW || fetchPolicy == ONLY_CACHED) {
            throw IllegalArgumentException("This function supports only UP_TO_DATE and PREFER_CACHED fetch policies")
        }
        val frameCount = correctFrameCount(animationCoversMinutes)
        val (_, htmlMaybe) = fetchString(context, "$url/index.htm", fetchPolicy)
        val html = htmlMaybe ?: return@flow
        val imgIds = imgIdRegex.findAll(html).map { it.value }.toList()
        val imgTimestamps = imgTsRegex.findAll(html).map { dateFormat.parse(it.value) }.toList()
        val frames = imgTimestamps.zip(imgIds).take(frameCount).reversed().map { (ts, imgId) ->
            val tsMillis = LocalDateTime.from(ts).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
            val (_, imgBytes) = fetchBytes(context, "$url/IMAGESDisplay/$imgId", fetchPolicy)
            StdFrame(imgBytes ?: ByteArray(0), tsMillis)
        }
        emit(StdSequence(frames.toMutableList()))
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

        suspend fun fetchFrames(fetchPolicy: FetchPolicy): MutableList<StdFrame> {
            val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val frames = mutableListOf<StdFrame?>()
            val frameCount = correctFrameCount(animationCoversMinutes)

            suspend fun fetchNextFrame(): StdFrame {
                calendar.gotoPreviousHalfHour()
                val (_, bytes) = fetchBytes(context, calendar.toGifUrl(), fetchPolicy)
                if (bytes == null) {
                    throw ImageFetchException(null, HttpErrorResponse(404))
                }
                return StdFrame(bytes, calendar.timeInMillis)
            }

            suspend fun fetchNewestFrame(): StdFrame {
                val maxStepsToGoBack = 12
                for (i in 1..maxStepsToGoBack) {
                    try {
                        return fetchNextFrame()
                    } catch (e: ImageFetchException) {
                        if (e.httpResponseCode != 404 || i == maxStepsToGoBack) {
                            throw e
                        }
                    }
                }
                throw AssertionError("Should be unreachable")
            }

            val newestFrame = fetchNewestFrame()
            for (i in 1..frameCount) {
                frames +=
                    try {
                        fetchNextFrame()
                    } catch (e: ImageFetchException) {
                        null
                    }
            }
            frames.reverse()
            val nonNullFrames = withGapsFilledIn(frames, newestFrame)

            fun String.timeCode() = substring(length - 14, length - 4).toLong()

            val earliestFrameTimeCode = calendar.toGifUrl().timeCode()
            Log.i("zamg", "earliestFrameTimeCode $earliestFrameTimeCode")
            for (file in context.cacheDirFilesStartingWith(url)) {
                if (file.path.timeCode() < earliestFrameTimeCode) {
                    Log.i("zamg", "delete stale ${file.path}")
                    file.delete()
                }
            }
            return nonNullFrames.toMutableList()
        }

        Log.i("zamg", "fetchPolicy $fetchPolicy")
        val resultFrames = if (fetchPolicy == PREFER_CACHED) {
            try {
                fetchFrames(ONLY_CACHED).also {
                    Log.i("zamg", "Got cached images")
                }
            } catch (e: ImageFetchException) {
                Log.i("zamg", "Nothing in cache, fetching from the web")
                fetchFrames(UP_TO_DATE).also {
                    Log.i("zamg", "Got images from the web")
                }
            } catch (e: Exception) {
                Log.e("zamg", "Unexpected error", e)
                mutableListOf()
            }
        } else {
            fetchFrames(UP_TO_DATE)
        }
        emit(StdSequence(resultFrames))
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

    private fun withGapsFilledIn(frames: List<StdFrame?>, newestFrame: StdFrame): List<StdFrame> {
        return frames.mapIndexed { i, frame ->
            frame
                ?: (i - 1).downTo(0).map { frames[it] }.find { it != null }
                ?: (i + 1).until(frames.size).map { frames[it] }.find { it != null }
                ?: newestFrame
        }
    }
}
