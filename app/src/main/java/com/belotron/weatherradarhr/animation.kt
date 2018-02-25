package com.belotron.weatherradarhr

import android.annotation.SuppressLint
import android.graphics.Bitmap
import com.belotron.weatherradarhr.ImageBundle.Status.SHOWING
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

class AnimationLooper(
        private val imgBundles: List<ImageBundle>
) {
    private val animators = arrayOfNulls<GifAnimator>(imgBundles.size)
    private val animatorJobs = arrayOfNulls<Job>(imgBundles.size)
    private var loopingJob: Job? = null
    private var animationDuration: Int? = null

    fun receiveNewGif(desc: ImgDescriptor, gifData: ByteArray, isOffline: Boolean) {
        animators[desc.index] = GifAnimator(imgBundles, desc, gifData, isOffline)
    }

    fun restart(newDuration: Int, newRateMinsPerSec: Int) {
        animationDuration = newDuration
        animators.forEach { it?.rateMinsPerSec = newRateMinsPerSec }
        info { "AnimationLooper.restart" }
        if (animators.none()) {
            return
        }
        stop()
        loopingJob = launch(UI) {
            while (true) {
                animatorJobs.forEach { it?.join() }
                animators.forEachIndexed { i, it ->
                    animatorJobs[i] = it?.animate()
                }
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
        private val imgBundles: List<ImageBundle>,
        private val imgDesc: ImgDescriptor,
        gifData: ByteArray,
        private val isOffline: Boolean
) {
    var rateMinsPerSec: Int = DEFAULT_ANIMATION_RATE

    private val frameDelayMillis get() =  1000 * imgDesc.minutesPerFrame / rateMinsPerSec
    private val bitmapProvider = FreeLists()
    private val gifDecoder = StandardGifDecoder(bitmapProvider).apply { read(gifData) }
    private val imgBundle: ImageBundle get() = imgBundles[imgDesc.index]
    private var timestamp = 0L
    private var currFrame: Bitmap? = null
    private var currFrameShownAt = 0L

    fun animate(): Job? {
        return launch(UI) {
            try {
                gifDecoder.rewind()
                showFrame(gifDecoder.advanceAndDecode())
                updateAgeText()
            } catch (e: StopAnimationException) {
                info { "Animator stop: ${e.message} before loop" }
            }
            try {
                while (true) {
                    val nextFrame = gifDecoder.advanceAndDecode()
                    val elapsedSinceFrameShown = NANOSECONDS.toMillis(System.nanoTime() - currFrameShownAt)
                    val remainingTillNextFrame = frameDelayMillis - elapsedSinceFrameShown
                    debug { "$elapsedSinceFrameShown ms since last frame, $remainingTillNextFrame ms till next frame" }
                    remainingTillNextFrame.takeIf { it > 0 }?.also {
                        delay(it)
                    }
                    showFrame(nextFrame)
                }
            } catch (e: StopAnimationException) {
                debug { "Animator stop: ${e.message} inside the loop" }
            }
        }
    }

    private fun showFrame(newFrame: Bitmap) {
        imgBundle.imgView?.setImageBitmap(newFrame)
        currFrameShownAt = System.nanoTime()
        currFrame?.dispose()
        currFrame = newFrame
    }

    @SuppressLint("SetTextI18n")
    private suspend fun updateAgeText() {
        if (imgBundle.status != SHOWING) return
        ensureTimestampInitialized()
        imgBundle.textView?.apply {
            text = (if (isOffline) "Offline - " else "") + context.ageText(timestamp)
        }
    }

    private suspend fun ensureTimestampInitialized() {
        if (timestamp != 0L) return
        with(gifDecoder) {
            gotoLastFrame()
            decodeCurrentFrame().also {
                timestamp = imgDesc.ocrTimestamp(it)
                it.dispose()
            }
        }
    }

    private suspend fun GifDecoder.advanceAndDecode(): Bitmap {
        if (!advance()) {
            throw StopAnimationException("gifDecoder.advance() returned false")
        }
        return decodeCurrentFrame()
    }

    private suspend fun GifDecoder.decodeCurrentFrame() = withContext(threadPool) { currentFrame }

    private fun Bitmap.dispose() = bitmapProvider.release(this)
}

private class StopAnimationException(message: String) : Exception(message)

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
