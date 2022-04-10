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
    fun intoDecoder(allocator: Allocator): FrameDecoder<T>
}

interface FrameDecoder<T : Frame> {
    val sequence: FrameSequence<T>
    fun decodeFrame(frameIndex: Int): Bitmap
    fun assignTimestamp(frameIndex: Int, ocrTimestamp: (Pixels) -> Long)
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
    val frameWidth: Int,
    val frameHeight: Int
) : FrameSequence<PngFrame> {
    override fun intoDecoder(allocator: Allocator) = PngDecoder(allocator, this)
}

class PngDecoder(
    private val allocator: Allocator,
    override val sequence: PngSequence,
) : FrameDecoder<PngFrame> {

    override fun decodeFrame(frameIndex: Int): Bitmap {
        val frame = sequence.frames[frameIndex]
        return BitmapFactory.decodeByteArray(frame.pngBytes, 0, frame.pngBytes.size, BitmapFactory.Options().apply {
            inMutable = true
            inBitmap = allocator.obtain(sequence.frameWidth, sequence.frameHeight, Bitmap.Config.ARGB_8888)
        })
    }
    override fun assignTimestamp(frameIndex: Int, ocrTimestamp: (Pixels) -> Long) {
        val bitmap = decodeFrame(frameIndex)
        try {
            sequence.frames[frameIndex].timestamp = ocrTimestamp(bitmap.asPixels())
        } finally {
            release(bitmap)
        }
    }

    override fun release(bitmap: Bitmap) {
        allocator.release(bitmap)
    }

    override fun dispose() {}
}
