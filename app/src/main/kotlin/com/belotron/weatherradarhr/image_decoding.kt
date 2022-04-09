package com.belotron.weatherradarhr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.belotron.weatherradarhr.gifdecode.Allocator
import com.belotron.weatherradarhr.gifdecode.Pixels

interface Frame {
    var timestamp: Long
}

interface FrameSequence<T: Frame> {
    val frames: MutableList<T>
    fun intoDecoder(allocator: Allocator): FrameDecoder<T>
}

interface FrameDecoder<T: Frame> {
    val sequence: FrameSequence<T>
    fun decodeToBitmap(frameIndex: Int): Bitmap
    fun decodeToPixels(frameIndex: Int): Pixels
    fun dispose()
}

val <T: Frame> FrameDecoder<T>.frameCount: Int get() = sequence.frames.size

class PngFrame(
    val pngBytes: ByteArray,
) : Frame {
    override var timestamp = 0L
}

class PngSequence(
    override val frames: MutableList<PngFrame>
) : FrameSequence<PngFrame> {
    override fun intoDecoder(allocator: Allocator): FrameDecoder<PngFrame> = PngDecoder(allocator, this)
}

class PngDecoder(
    private val allocator: Allocator,
    override val sequence: PngSequence
) : FrameDecoder<PngFrame> {
    private val width = 720
    private val height = 751

    override fun decodeToBitmap(frameIndex: Int): Bitmap {
        val frame = sequence.frames[frameIndex]
        return BitmapFactory.decodeByteArray(frame.pngBytes, 0, frame.pngBytes.size, BitmapFactory.Options().apply {
            inMutable = true
            inBitmap = allocator.obtain(width, height, Bitmap.Config.ARGB_8888)
        })
    }

    override fun decodeToPixels(frameIndex: Int): Pixels {
        val frame = sequence.frames[frameIndex]
        return BitmapFactory.decodeByteArray(frame.pngBytes, 0, frame.pngBytes.size).asPixels()
    }

    override fun dispose() {}
}
