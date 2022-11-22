package com.belotron.weatherradarhr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.belotron.weatherradarhr.gifdecode.Allocator
import com.belotron.weatherradarhr.gifdecode.Pixels
import kotlin.properties.Delegates

interface Frame {
    val timestamp: Long
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
    private val pngBytes: ByteArray,
) : Frame {
    private val width: Int
    private val height: Int

    override var timestamp by Delegates.notNull<Long>()

    init {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds }
        BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, opts)
        opts.apply {
            if (outWidth <= 0 || outHeight <= 0) {
                throw ImageDecodeException("Image has no pixels. width: $outWidth height: $outHeight")
            }
            width = outWidth
            height = outHeight
        }
    }

    fun decode(allocator: Allocator): Bitmap {
        return BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, BitmapFactory.Options().apply {
            inMutable = true
            inBitmap = allocator.obtain(width, height, Bitmap.Config.ARGB_8888)
        })
    }
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

    override fun decodeFrame(frameIndex: Int): Bitmap = sequence.frames[frameIndex].decode(allocator)

    override fun assignTimestamp(frameIndex: Int) {
        val frame = sequence.frames[frameIndex]
        val bitmap = frame.decode(allocator)
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
