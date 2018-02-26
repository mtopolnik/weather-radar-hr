package com.belotron.weatherradarhr.gifdecode

import android.graphics.Bitmap
import com.belotron.weatherradarhr.debug
import java.util.ArrayDeque
import java.util.Queue

private val emptyByteArray = ByteArray(0)
private val emptyIntArray = IntArray(0)

class BitmapFreelists : GifDecoder.BitmapProvider {

    private val bitmapQueues = HashMap<Pair<Int, Int>, ArrayDeque<Bitmap>>()
    private val byteArrayQueues = HashMap<Int, Queue<ByteArray>>()
    private val intArrayQueues = HashMap<Int, Queue<IntArray>>()

    override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap {
        debug { "Obtain $width x $height bitmap" }
        val bitmap = bitmapQueues[Pair(width, height)]?.poll()
        return bitmap?.apply { this.config = config }
                ?: Bitmap.createBitmap(width, height, config)
    }

    override fun release(bitmap: Bitmap) {
        debug { "Release ${bitmap.width} x ${bitmap.height} bitmap" }
        val key = Pair(bitmap.width, bitmap.height)
        val freelist = bitmapQueues[key] ?: ArrayDeque<Bitmap>().also { bitmapQueues[key] = it }
        if (freelist.any { bitmap === it }) {
            throw IllegalStateException("Double release of bitmap")
        }
        freelist.add(bitmap)
    }

    override fun obtainByteArray(size: Int): ByteArray {
        debug { "Obtain $size bytes" }
        return if (size == 0) emptyByteArray
        else byteArrayQueues[size]?.poll() ?: ByteArray(size)
    }

    override fun release(bytes: ByteArray) {
        debug { "Release ${bytes.size} bytes" }
        val freelist = byteArrayQueues[bytes.size] ?: ArrayDeque<ByteArray>().also { byteArrayQueues[bytes.size] = it }
        if (freelist.any { bytes === it }) {
            throw IllegalStateException("Double release of ByteArray")
        }
        freelist.add(bytes)
    }

    override fun obtainIntArray(size: Int): IntArray {
        debug { "Obtain $size ints" }
        return if (size == 0) emptyIntArray
        else intArrayQueues[size]?.poll() ?: IntArray(size)
    }

    override fun release(array: IntArray) {
        debug { "Release ${array.size} ints" }
        val freelist = intArrayQueues[array.size] ?: ArrayDeque<IntArray>().also { intArrayQueues[array.size] = it }
        if (freelist.any { array === it }) {
            throw IllegalStateException("Double release of IntArray")
        }
        freelist.add(array)
    }
}
