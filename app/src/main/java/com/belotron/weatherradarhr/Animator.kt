package com.belotron.weatherradarhr

import android.graphics.Bitmap
import android.widget.ImageView
import com.belotron.weatherradarhr.gifdecode.GifDecoder
import com.belotron.weatherradarhr.gifdecode.GifDecoder.STATUS_OK
import com.belotron.weatherradarhr.gifdecode.StandardGifDecoder
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import java.util.ArrayDeque
import java.util.Queue
import java.util.concurrent.TimeUnit.NANOSECONDS

class Animator(
        gifData: ByteArray,
        private val imgViews: Array<ImageView?>,
        private val viewIndex: Int
) {
    private val bitmapProvider = FreeLists()
    private val gifDecoder = StandardGifDecoder(bitmapProvider)
            .apply { read(gifData) }

    fun animate() {
        var currFrameShownAt = 0L
        (imgViews[viewIndex] ?: return).apply {
            gifDecoder.advance()
            setImageBitmap(gifDecoder.nextFrame)
            currFrameShownAt = System.nanoTime()
            parent.requestLayout()
        }
        var currFrame: Bitmap? = null
        launch(UI) {
            while (true) {
                val currDelay = gifDecoder.nextDelay
                gifDecoder.advance()
                val nextFrame = gifDecoder.nextFrame
                if (gifDecoder.status != STATUS_OK) {
                    MyLog.e("GIF decoder failed with status ${gifDecoder.status}")
                    break;
                }
                val elapsedSinceLastFrame = NANOSECONDS.toMillis(System.nanoTime() - currFrameShownAt)
                val timeTillNextFrame = currDelay - elapsedSinceLastFrame
                MyLog.i("$elapsedSinceLastFrame ms since last frame, $timeTillNextFrame ms till next frame")
                if (timeTillNextFrame > 0) {
                    delay(timeTillNextFrame)
                }
                val view = imgViews[viewIndex] ?: break
                view.setImageBitmap(nextFrame)
                currFrameShownAt = System.nanoTime()
                currFrame?.also { bitmapProvider.release(it) }
                currFrame = nextFrame
            }
        }
    }
}

private val emptyByteArray = ByteArray(0)
private val emptyIntArray = IntArray(0)

class FreeLists() : GifDecoder.BitmapProvider {

    private val bitmapQueues = HashMap<Pair<Int, Int>, ArrayDeque<Bitmap>>()
    private val byteArrayQueues = HashMap<Int, Queue<ByteArray>>()
    private val intArrayQueues = HashMap<Int, Queue<IntArray>>()

    override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap {
        MyLog.i("Obtain bitmap ${width}x$height")
        return bitmapQueues[Pair(width, height)]?.poll()?.apply { this.config = config }
                ?: Bitmap.createBitmap(width, height, config)
    }

    override fun release(bitmap: Bitmap) {
        MyLog.i("Release bitmap ${bitmap.width}x${bitmap.height}")
        val key = Pair(bitmap.width, bitmap.height)
        bitmapQueues[key] ?: ArrayDeque<Bitmap>().also { bitmapQueues[key] = it }
                .add(bitmap)
    }

    override fun obtainByteArray(size: Int): ByteArray {
        return if (size == 0) emptyByteArray
        else byteArrayQueues[size]?.poll() ?: ByteArray(size)
    }

    override fun release(bytes: ByteArray) {
        byteArrayQueues[bytes.size] ?: ArrayDeque<ByteArray>().also { byteArrayQueues[bytes.size] = it }
                .add(bytes)
    }

    override fun obtainIntArray(size: Int): IntArray {
        return if (size == 0) emptyIntArray
        else intArrayQueues[size]?.poll() ?: IntArray(size)
    }

    override fun release(array: IntArray) {
        intArrayQueues[array.size] ?: ArrayDeque<IntArray>().also { intArrayQueues[array.size] = it }
                .add(array)
    }
}
