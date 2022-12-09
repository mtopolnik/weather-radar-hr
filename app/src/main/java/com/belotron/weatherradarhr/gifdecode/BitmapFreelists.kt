package com.belotron.weatherradarhr.gifdecode

import android.graphics.Bitmap
import com.belotron.weatherradarhr.debug
import java.util.*

private val emptyByteArray = ByteArray(0)
private val emptyIntArray = IntArray(0)

class BitmapFreelists : Allocator {

    private val bitmapQueues = HashMap<Pair<Int, Int>, ArrayDeque<Bitmap>>()
    private val byteArrayQueues = HashMap<Int, Queue<ByteArray>>()
    private val intArrayQueues = HashMap<Int, Queue<IntArray>>()

    override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap = synchronized (bitmapQueues) {
        debug { "Obtain $width x $height bitmap" }
        bitmapQueues[Pair(width, height)]
                ?.poll()
                ?.apply { this.config = config }
                ?: Bitmap.createBitmap(width, height, config)
    }

    override fun release(bitmap: Bitmap): Unit = synchronized(bitmapQueues) {
        debug { "Release ${bitmap.width} x ${bitmap.height} bitmap" }
        val key = Pair(bitmap.width, bitmap.height)
        val freelist = bitmapQueues[key] ?: ArrayDeque<Bitmap>().also { bitmapQueues[key] = it }
        if (freelist.any { bitmap === it }) {
            throw IllegalStateException("Double release of bitmap")
        }
        freelist.add(bitmap)
    }

    override fun obtainByteArray(size: Int): ByteArray = synchronized(byteArrayQueues) {
        debug { "Obtain $size bytes" }
        if (size == 0) emptyByteArray
        else byteArrayQueues[size]?.poll() ?: ByteArray(size)
    }

    override fun release(bytes: ByteArray): Unit = synchronized(byteArrayQueues) {
        debug { "Release ${bytes.size} bytes" }
        val freelist = byteArrayQueues[bytes.size] ?: ArrayDeque<ByteArray>().also { byteArrayQueues[bytes.size] = it }
        if (freelist.any { bytes === it }) {
            throw IllegalStateException("Double release of ByteArray")
        }
        freelist.add(bytes)
    }

    override fun obtainIntArray(size: Int): IntArray = synchronized(intArrayQueues) {
        debug { "Obtain $size ints" }
        if (size == 0) emptyIntArray
        else intArrayQueues[size]?.poll() ?: IntArray(size)
    }

    override fun release(array: IntArray): Unit = synchronized(intArrayQueues)  {
        debug { "Release ${array.size} ints" }
        val freelist = intArrayQueues[array.size] ?: ArrayDeque<IntArray>().also { intArrayQueues[array.size] = it }
        if (freelist.any { array === it }) {
            throw IllegalStateException("Double release of IntArray")
        }
        freelist.add(array)
    }

    override fun dispose() {
        // Explicit disposal isn't needed, Android handles it with regular GC.
        // Eager disposal seems to crash the app occasionally.
//        bitmapQueues.values.forEach { freelist ->
//            freelist.forEach { bitmap ->
//                bitmap.recycle()
//            }
//        }
//        bitmapQueues.clear()
//        byteArrayQueues.clear()
//        intArrayQueues.clear()
    }
}
