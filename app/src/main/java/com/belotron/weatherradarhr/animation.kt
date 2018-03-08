package com.belotron.weatherradarhr

import android.graphics.Bitmap
import com.belotron.weatherradarhr.ImageBundle.Status.SHOWING
import com.belotron.weatherradarhr.gifdecode.BitmapFreelists
import com.belotron.weatherradarhr.gifdecode.StandardGifDecoder
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.withContext
import java.util.concurrent.TimeUnit.NANOSECONDS

class AnimationLooper(
        private val imgBundles: List<ImageBundle>
) {
    private val animators = arrayOfNulls<GifAnimator>(imgBundles.size)
    private val animatorJobs = arrayOfNulls<Job>(imgBundles.size)
    private var loopingJob: Job? = null

    fun receiveNewGif(desc: ImgDescriptor, gifData: ByteArray, isOffline: Boolean) {
        animators[desc.index] = GifAnimator(imgBundles, desc, gifData, isOffline)
    }

    fun restart(newRateMinsPerSec: Int, newFreezeTimeMillis: Int) {
        info { "AnimationLooper.restart" }
        if (animators.none()) {
            return
        }
        animators.forEach {
            it?.rateMinsPerSec = newRateMinsPerSec
            it?.freezeTimeMillis = newFreezeTimeMillis
        }
        stop()
        var oldLoopingJob = loopingJob
        loopingJob = launch(UI) {
            oldLoopingJob?.join()
            oldLoopingJob = null
            while (true) {
                animatorJobs.forEach { it?.join() }
                animators.forEachIndexed { i, it ->
                    animatorJobs[i] = it?.animate()
                }
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
    var rateMinsPerSec: Int = 20
    var freezeTimeMillis: Int = 0

    private val frameDelayMillis get() =  1000 * imgDesc.minutesPerFrame / rateMinsPerSec
    private val bitmapProvider = BitmapFreelists()
    private val gifDecoder = StandardGifDecoder(bitmapProvider).apply { read(gifData) }
    private val imgBundle: ImageBundle get() = imgBundles[imgDesc.index]
    private var timestamp = 0L
    private var currFrame: Bitmap? = null

    fun animate(startIndex: Int = 0): Job? {
        require(startIndex in 0 until gifDecoder.frameCount) { "startIndex out of range: $startIndex" }
        return launch(UI) {
            updateAgeText()
            var frame = suspendDecodeFrame(startIndex)
            (startIndex + 1 .. gifDecoder.frameCount).forEach { i ->
                showFrame(frame)
                val frameShownAt = System.nanoTime()
                val lastFrameShown = i == gifDecoder.frameCount
                if (!lastFrameShown) {
                    frame = suspendDecodeFrame(i)
                }
                val elapsedSinceFrameShown = NANOSECONDS.toMillis(System.nanoTime() - frameShownAt)
                val targetDelay =
                        if (lastFrameShown) Math.max(freezeTimeMillis, frameDelayMillis)
                        else frameDelayMillis
                val remainingDelay = targetDelay - elapsedSinceFrameShown
                debug { "$elapsedSinceFrameShown ms since last frame, $remainingDelay ms till next frame" }
                remainingDelay.takeIf { it > 0 }?.also {
                    delay(it)
                }
            }
        }
    }

    private fun showFrame(newFrame: Bitmap) {
        imgBundle.imgView?.setImageBitmap(newFrame)
        currFrame?.dispose()
        currFrame = newFrame
    }

    private suspend fun updateAgeText() {
        imgBundle.takeIf { it.status == SHOWING }?.textView?.setAgeText(suspendGetTimestamp(), isOffline)
    }

    private suspend fun suspendGetTimestamp(): Long {
        if (timestamp == 0L) {
            suspendDecodeFrame(gifDecoder.frameCount - 1).also {
                timestamp = imgDesc.ocrTimestamp(it)
                it.dispose()
            }
        }
        return timestamp
    }

    private suspend fun suspendDecodeFrame(frameIndex: Int) =
            withContext(threadPool) { gifDecoder.decodeFrame(frameIndex) }

    private fun Bitmap.dispose() = bitmapProvider.release(this)
}
