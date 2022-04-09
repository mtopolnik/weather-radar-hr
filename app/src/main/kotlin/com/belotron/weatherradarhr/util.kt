package com.belotron.weatherradarhr

import android.content.Context
import android.graphics.Rect
import android.view.View
import com.belotron.weatherradarhr.CcOption.CC_PRIVATE
import com.belotron.weatherradarhr.gifdecode.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.*
import java.util.Collections.addAll

suspend fun <T: Frame> FrameSequence<T>.assignTimestamps(context: Context, desc: ImgDescriptor) {
    val frameSequence = this
    val allocator = BitmapFreelists()
    coroutineScope {
        (0 until frames.size).map { frameIndex ->
            async(Dispatchers.Default) {
                context.decodeAndAssignTimestamp(frameSequence, frameIndex, desc, allocator)
            }
        }.forEachIndexed { i, it ->
            frames[i].timestamp = it.await()
        }
    }
}

private fun Context.decodeAndAssignTimestamp(
    frameSequence: FrameSequence<out Frame>, frameIndex: Int, desc: ImgDescriptor, allocator: Allocator
): Long {
    return try {
        val decoder = frameSequence.intoDecoder(allocator)
        decoder.decodeToPixels(frameIndex).let {
            desc.ocrTimestamp(it)
        }.also {
            decoder.dispose()
        }
    } catch (e: ImgDecodeException) {
        severe(CC_PRIVATE) { "Animated GIF decoding error" }
        invalidateCache(desc.url)
        throw e
    }
}

fun <T: Frame> FrameSequence<T>.sortAndDeduplicateFrames() {
    val sortedFrames = TreeSet(compareBy(Frame::timestamp)).apply {
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
