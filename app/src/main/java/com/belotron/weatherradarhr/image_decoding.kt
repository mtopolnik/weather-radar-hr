package com.belotron.weatherradarhr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.belotron.weatherradarhr.gifdecode.Allocator
import com.belotron.weatherradarhr.gifdecode.Pixels

interface Frame {
    var timestamp: Long
}

interface FrameSequence<T : Frame> {
    val frames: MutableList<T>
    fun intoDecoder(allocator: Allocator, ocrTimestamp: (Pixels) -> Long): FrameDecoder<T>
}

interface FrameDecoder<T : Frame> {
    val sequence: FrameSequence<T>
    fun decodeFrame(frameIndex: Int): Bitmap
    fun assignTimestamp(frameIndex: Int)
    fun release(bitmap: Bitmap)
    fun dispose()
}

val <T : Frame> FrameDecoder<T>.frameCount: Int get() = sequence.frames.size

class PngFrame(
    val pngBytes: ByteArray,
) : Frame {
    override var timestamp = 0L
}

class PngSequence(
    override val frames: MutableList<PngFrame>,
) : FrameSequence<PngFrame> {
    override fun intoDecoder(allocator: Allocator, ocrTimestamp: (Pixels) -> Long) =
        PngDecoder(allocator, this, ocrTimestamp)
}

class PngDecoder(
    private val allocator: Allocator,
    override val sequence: PngSequence,
    private val ocrFun: (Pixels) -> Long
) : FrameDecoder<PngFrame> {

    override fun decodeFrame(frameIndex: Int): Bitmap = decodeFrame(sequence.frames[frameIndex], allocator)

    override fun assignTimestamp(frameIndex: Int) {
        val frame = sequence.frames[frameIndex]
        val bitmap = decodeFrame(frame, allocator)
        try {
            frame.timestamp = ocrTimestamp(bitmap, ocrFun)
        } finally {
            release(bitmap)
        }
    }

    override fun release(bitmap: Bitmap) {
        allocator.release(bitmap)
    }

    override fun dispose() {
        allocator.dispose()
    }
}

fun ocrTimestamp(bitmap: Bitmap, ocrFun: (Pixels) -> Long) = ocrFun(bitmap.asPixels())

fun decodeFrame(frame: PngFrame, allocator: Allocator): Bitmap {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds }
    BitmapFactory.decodeByteArray(frame.pngBytes, 0, frame.pngBytes.size, opts)
    return BitmapFactory.decodeByteArray(frame.pngBytes, 0, frame.pngBytes.size, BitmapFactory.Options().apply {
        inMutable = true
        inBitmap = allocator.obtain(opts.outWidth, opts.outHeight, Bitmap.Config.ARGB_8888)
    })
}
