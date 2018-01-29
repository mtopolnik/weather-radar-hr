package com.belotron.weatherradarhr

import android.graphics.Bitmap
import android.widget.ImageView
import com.belotron.weatherradarhr.gifdecode.GifDecoder
import com.belotron.weatherradarhr.gifdecode.StandardGifDecoder
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import java.util.ArrayDeque
import java.util.Queue
import java.util.concurrent.TimeUnit.NANOSECONDS

class AnimationLooper {
    val animators = arrayOfNulls<GifAnimator>(2)
    private val animatorJobs = arrayOfNulls<Job>(2)
    private var loopingJob: Job? = null

    fun restart(animationDuration: Int, frameDelayFactor: Int) {
        MyLog.i("AnimationLooper.restart. Animation duration $animationDuration")
        if (animators.none()) {
            return
        }
        cancel()
        loopingJob = start {
            while (true) {
                animatorJobs.forEach { it?.join() }
                animators.forEachIndexed { i, it ->
                    it?.frameDelayFactor = frameDelayFactor
                    animatorJobs[i] = it?.animate()
                }
                delay(animationDuration)
            }
        }
    }

    fun cancel() {
        animatorJobs.forEach { it?.cancel() }
        loopingJob?.cancel()
    }
}

class GifAnimator(
        gifData: ByteArray,
        private val imgViews: Array<ImageView?>,
        private val imgDesc: ImgDescriptor
) {
    var frameDelayFactor: Int = 100
    private val frameDelay: Int
        get() = frameDelayFactor * imgDesc.minutesPerFrame
    private val bitmapProvider = FreeLists()
    private val gifDecoder = StandardGifDecoder(bitmapProvider).apply { read(gifData) }
    private var currFrame: Bitmap? = null
    private var currFrameShownAt = 0L

    fun animate(): Job {
        gifDecoder.apply {
            rewind()
            advance()
        }
        return launch(UI) coroutine@ {
            if (!replaceCurrentFrame(gifDecoder.nextFrame)) {
                MyLog.i("Animator stop: replaceCurrentFrame() returned false")
                return@coroutine
            }
            imgViews[imgDesc.index]!!.parent.requestLayout()
            while (true) {
                if (!gifDecoder.advance()) {
                    MyLog.i("Animator stop: gifDecoder.advance() returned false")
                    break
                }
                val nextFrame = gifDecoder.nextFrame
                val elapsedSinceFrameShown = NANOSECONDS.toMillis(System.nanoTime() - currFrameShownAt)
                val remainingTillNextFrame = frameDelay - elapsedSinceFrameShown
                MyLog.d("$elapsedSinceFrameShown ms since last frame, $remainingTillNextFrame ms till next frame")
                remainingTillNextFrame.takeIf({ it > 0 })?.also {
                    delay(it)
                }
                if (!replaceCurrentFrame(nextFrame)) {
                    break
                }
            }
        }
    }

    private fun replaceCurrentFrame(nextFrame: Bitmap): Boolean {
        val view = imgViews[imgDesc.index] ?: return false
        view.setImageBitmap(nextFrame)
        currFrameShownAt = System.nanoTime()
        currFrame?.also { bitmapProvider.release(it) }
        currFrame = nextFrame
        return true
    }
}

private val emptyByteArray = ByteArray(0)
private val emptyIntArray = IntArray(0)

class FreeLists : GifDecoder.BitmapProvider {

    private val bitmapQueues = HashMap<Pair<Int, Int>, ArrayDeque<Bitmap>>()
    private val byteArrayQueues = HashMap<Int, Queue<ByteArray>>()
    private val intArrayQueues = HashMap<Int, Queue<IntArray>>()

    override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap {
        MyLog.d("Obtain $width x $height bitmap")
        val bitmap = bitmapQueues[Pair(width, height)]?.poll()
        return bitmap?.apply { this.config = config }
                ?: Bitmap.createBitmap(width, height, config)
    }

    override fun release(bitmap: Bitmap) {
        MyLog.d("Release ${bitmap.width} x ${bitmap.height} bitmap")
        val key = Pair(bitmap.width, bitmap.height)
        val freelist = bitmapQueues[key] ?: ArrayDeque<Bitmap>().also { bitmapQueues[key] = it }
        if (freelist.any { bitmap === it }) {
            throw IllegalStateException("Double release of bitmap")
        }
        freelist.add(bitmap)
    }

    override fun obtainByteArray(size: Int): ByteArray {
        MyLog.d("Obtain $size bytes")
        return if (size == 0) emptyByteArray
        else byteArrayQueues[size]?.poll() ?: ByteArray(size)
    }

    override fun release(bytes: ByteArray) {
        MyLog.d("Release ${bytes.size} bytes")
        val freelist = byteArrayQueues[bytes.size] ?: ArrayDeque<ByteArray>().also { byteArrayQueues[bytes.size] = it }
        if (freelist.any { bytes === it }) {
            throw IllegalStateException("Double release of ByteArray")
        }
        freelist.add(bytes)
    }

    override fun obtainIntArray(size: Int): IntArray {
        MyLog.d("Obtain $size ints")
        return if (size == 0) emptyIntArray
        else intArrayQueues[size]?.poll() ?: IntArray(size)
    }

    override fun release(array: IntArray) {
        MyLog.d("Release ${array.size} ints")
        val freelist = intArrayQueues[array.size] ?: ArrayDeque<IntArray>().also { intArrayQueues[array.size] = it }
        if (freelist.any { array === it }) {
            throw IllegalStateException("Double release of IntArray")
        }
        freelist.add(array)
    }
}
