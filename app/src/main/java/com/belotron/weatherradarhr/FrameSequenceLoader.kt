package com.belotron.weatherradarhr

import android.content.Context
import com.belotron.weatherradarhr.CcOption.CC_PRIVATE
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
import java.util.*
import kotlin.math.ceil

private const val SEQUENCE_RETRY_DELAY_MILLIS = 7_000L

enum class Outcome {
    SUCCESS, PARTIAL_SUCCESS, FAILURE
}

sealed class FrameSequenceLoader(
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
}

class HrSequenceLoader(
    urlKeyword: String,
    ocrTimestamp: (Pixels) -> Long,
) : FrameSequenceLoader(
    minutesPerFrame = 5,
    ocrTimestamp
) {
    private val url = "https://vrijeme.hr/anim_${urlKeyword}.gif"

    override fun incrementallyFetchFrameSequence(
        context: Context, animationCoversMinutes: Int, fetchPolicy: FetchPolicy
    ): Flow<PngSequence?> = flow {

        suspend fun fetchSequence(): PngSequence? {
            while (true) {
                val (outcome, gifSequence) = try {
                    Pair(SUCCESS, fetchGifSequence(context, url, fetchPolicy))
                } catch (e: ImageFetchException) {
                    val cached = e.cached as GifSequence?
                    Pair(if (cached != null) PARTIAL_SUCCESS else FAILURE, cached)
                }
                if (outcome != SUCCESS) {
                    delay(SEQUENCE_RETRY_DELAY_MILLIS)
                    continue
                }
                if (gifSequence == null)
                    return null

                val allocator = BitmapFreelists()
                val decoder = gifSequence.intoDecoder(allocator, ocrTimestamp)
                val pngFrames = coroutineScope {
                    // semaphore limits the number of simultaneous bitmaps
                    val semaphore = Semaphore(Runtime.getRuntime().availableProcessors())
                    val pngFrameTasks = mutableListOf<Deferred<PngFrame>>()
                    try {
                        val frames = gifSequence.frames
                        withContext(Default) {
                            (0 until frames.size).forEach { frameIndex ->
                                val bitmap = decoder.assignTimestampAndGetBitmap(frameIndex)
                                semaphore.acquire()
                                pngFrameTasks.add(async {
                                    try {
                                        PngFrame(bitmap.toCompressedBytes(), frames[frameIndex].timestamp)
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
                        decoder.dispose()
                    }
                    pngFrameTasks.map { it.await() }.toMutableList()
                }
                // HR animated gif has repeated frames at the end, remove the duplicates
                val sortedFrames = TreeSet(compareBy(PngFrame::timestamp)).apply {
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
                return PngSequence(pngFrames)
            }
        }

        emit(fetchSequence())
    }
}

class SloSequenceLoader : FrameSequenceLoader(
    minutesPerFrame = 5,
    ocrTimestamp = SloOcr::ocrSloTimestamp
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
                } catch (e: ImageDecodeException) {
                    severe(CC_PRIVATE) { "Error decoding animated GIF: ${e.message}" }
                    withContext(IO) {
                        context.invalidateCache(url)
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
