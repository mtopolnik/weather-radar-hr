package com.belotron.weatherradarhr

import android.graphics.Rect
import android.view.View
import java.util.*
import java.util.Collections.addAll

fun <T : Frame> FrameSequence<T>.sortAndDeduplicateFrames() {
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
