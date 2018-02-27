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
        var oldLoopingJob = loopingJob
        loopingJob = launch(UI) {
            oldLoopingJob?.join()
            oldLoopingJob = null
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
    private val bitmapProvider = BitmapFreelists()
    private val gifDecoder = StandardGifDecoder(bitmapProvider).apply { read(gifData) }
    private val imgBundle: ImageBundle get() = imgBundles[imgDesc.index]
    private var timestamp = 0L
    private var currFrame: Bitmap? = null
    private var currFrameShownAt = 0L

    fun animate(): Job? {
        return launch(UI) {
            try {
                showFrame(suspendDecodeFrame(0))
                updateAgeText()
            } catch (e: StopAnimationException) {
                info { "Animator stop: ${e.message} before loop" }
            }
            try {
                (1 until gifDecoder.frameCount).forEach { i ->
                    val nextFrame = suspendDecodeFrame(i)
                    val elapsedSinceFrameShown = NANOSECONDS.toMillis(System.nanoTime() - currFrameShownAt)
                    val remainingTillNextFrame = frameDelayMillis - elapsedSinceFrameShown
                    debug { "$elapsedSinceFrameShown ms since last frame, $remainingTillNextFrame ms till next frame" }
                    remainingTillNextFrame.takeIf { it > 0 }?.also {
                        delay(it)
                    }
                    showFrame(nextFrame)
                }
            } catch (e: StopAnimationException) {
                info { "Animator stop: ${e.message} inside the loop" }
            }
        }
    }

    private fun showFrame(newFrame: Bitmap) {
        imgBundle.imgView?.setImageBitmap(newFrame)
        currFrameShownAt = System.nanoTime()
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

private class StopAnimationException(message: String) : Exception(message)
