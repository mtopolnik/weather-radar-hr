package com.belotron.weatherradarhr

import android.content.Context
import android.graphics.Rect
import android.view.View
import com.belotron.weatherradarhr.CcOption.CC_PRIVATE
import com.belotron.weatherradarhr.gifdecode.Allocator
import com.belotron.weatherradarhr.gifdecode.BitmapFreelists
import com.belotron.weatherradarhr.gifdecode.GifDecoder
import com.belotron.weatherradarhr.gifdecode.GifFrame
import com.belotron.weatherradarhr.gifdecode.ImgDecodeException
import com.belotron.weatherradarhr.gifdecode.ParsedGif
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.TreeSet

suspend fun ParsedGif.assignTimestamps(context: Context, desc: ImgDescriptor) {
    val parsedGif = this
    val allocator = BitmapFreelists()
    coroutineScope {
        (0 until frameCount).map { frameIndex ->
            async(Dispatchers.Default) {
                context.decodeAndAssignTimestamp(parsedGif, frameIndex, desc, allocator)
            }
        }.forEachIndexed { i, it ->
            frames[i].timestamp = it.await()
        }
    }
}

private fun Context.decodeAndAssignTimestamp(
        parsedGif: ParsedGif, frameIndex: Int, desc: ImgDescriptor, allocator: Allocator
): Long {
    return try {
        GifDecoder(allocator, parsedGif).decodeFrame(frameIndex).let { decoder ->
            desc.ocrTimestamp(decoder.asPixels()).also { _ ->
                decoder.dispose()
            }
        }
    } catch (e: ImgDecodeException) {
        severe(CC_PRIVATE) { "Animated GIF decoding error" }
        invalidateCache(desc.url)
        throw e
    }
}

fun ParsedGif.sortAndDeduplicateFrames() {
    val sortedFrames = TreeSet<GifFrame>(compareBy(GifFrame::timestamp)).apply {
        addAll(frames)
    }
    frames.apply {
        clear()
        addAll(sortedFrames)
    }
}

fun Rect.reset(): Rect {
    set(0, 0, 0, 0)
    return this
}

fun View.isDescendantOf(that: View): Boolean {
    if (this === that) {
        return true
    }
    var currParent: View? = parent as? View
    while (currParent != null) {
        if (currParent === that) {
            return true
        }
        currParent = currParent.parent as? View
    }
    return false
}
