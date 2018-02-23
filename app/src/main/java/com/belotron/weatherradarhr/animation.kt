package com.belotron.weatherradarhr

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.widget.ImageView
import android.widget.TextView
import com.belotron.weatherradarhr.gifdecode.GifDecoder
import com.belotron.weatherradarhr.gifdecode.StandardGifDecoder
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.withContext
import java.util.ArrayDeque
import java.util.Queue
import java.util.concurrent.TimeUnit.NANOSECONDS

class AnimationLooper(numViews: Int) {
    val animators = arrayOfNulls<GifAnimator>(numViews)
    private val animatorJobs = arrayOfNulls<Job>(numViews)
    private var loopingJob: Job? = null
    private var animationDuration: Int? = null

    fun restart(newDuration: Int, newDelayFactor: Int) {
        animationDuration = newDuration
        animators.forEach { it?.frameDelayFactor = newDelayFactor }
        restart()
    }

    fun restart() {
        info { "AnimationLooper.restart" }
        if (animators.none()) {
            return
        }
        stop()
        loopingJob = start {
            while (true) {
                animatorJobs.forEach { it?.join() }
                animators.forEachIndexed { i, it ->
                    animatorJobs[i] = it?.animate()
                }
                delay(animationDuration!!)
            }
        }
    }

    fun animateOne(index: Int) {
        info { "AnimationLooper.animateOne $index" }
        stop()
        val animator = animators[index]!!
        loopingJob = start {
            while (true) {
                animatorJobs[index]?.join()
                animatorJobs[index] = animator.animate()
                delay(animationDuration!!)
            }
        }
    }

    fun stop() {
        animatorJobs.forEach { it?.cancel() }
        loopingJob?.cancel()
    }
}

class GifAnimator(
        private val imgDesc: ImgDescriptor,
        gifData: ByteArray,
        var imgView: ImageView?,
        private val isOffline: Boolean
) {
    private val bitmapProvider = FreeLists()
    private val gifDecoder = StandardGifDecoder(bitmapProvider).apply { read(gifData) }
    private val timestamp: Long = with(gifDecoder) {
        gotoLastFrame()
        currentFrame.let {
            val result = imgDesc.ocrTimestamp(it)
            bitmapProvider.release(it)
            resetFrameIndex()
            result
        }
    }
    var frameDelayFactor: Int = 100
    private val frameDelay get() = frameDelayFactor * imgDesc.minutesPerFrame

    private var currFrame: Bitmap? = null
    private var currFrameShownAt = 0L

    fun pushAgeTextToView(textView: TextView) {
        textView.text = (if (isOffline) "Offline - " else "") + textView.context.ageText(timestamp)
    }

    fun animate(): Job? {
        with(gifDecoder) {
                rewind()
                advance()
            }
        if (!replaceCurrentFrame(gifDecoder.currentFrame)) {
            info { "Animator stop: replaceCurrentFrame() returned false" }
            return null
        }
        return launch(UI) {
            while (true) {
                if (!gifDecoder.advance()) {
                    debug { "Animator stop: gifDecoder.advance() returned false" }
                    break
                }
                val nextFrame = withContext(threadPool) { gifDecoder.currentFrame }
                val elapsedSinceFrameShown = NANOSECONDS.toMillis(System.nanoTime() - currFrameShownAt)
                val remainingTillNextFrame = frameDelayMillis - elapsedSinceFrameShown
                debug { "$elapsedSinceFrameShown ms since last frame, $remainingTillNextFrame ms till next frame" }
                remainingTillNextFrame.takeIf { it > 0 }?.also {
                    delay(it)
                }
                if (!replaceCurrentFrame(nextFrame)) {
                    break
                }
            }
        }
    }

    private fun replaceCurrentFrame(nextFrame: Bitmap): Boolean {
        val view = imgView ?: return false
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
