package com.belotron.weatherradarhr

import android.graphics.Bitmap
import android.widget.SeekBar
import com.belotron.weatherradarhr.ImageBundle.Status.SHOWING
import com.belotron.weatherradarhr.gifdecode.BitmapFreelists
import com.belotron.weatherradarhr.gifdecode.StandardGifDecoder
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.withContext
import java.util.concurrent.TimeUnit.NANOSECONDS
import kotlin.math.roundToInt

class AnimationLooper(
        private val imgBundles: List<ImageBundle>
) : SeekBar.OnSeekBarChangeListener {

    private val animators = arrayOfNulls<GifAnimator>(imgBundles.size)
    private val animatorJobs = arrayOfNulls<Job>(imgBundles.size)
    private var loopingJob: Job? = null

    fun receiveNewGif(desc: ImgDescriptor, gifData: ByteArray, isOffline: Boolean) {
        animators[desc.index] = GifAnimator(imgBundles, desc, gifData, isOffline)
    }

    fun resume(newRateMinsPerSec: Int? = null, newFreezeTimeMillis: Int? = null) {
        info { "AnimationLooper.resume" }
        if (animators.none()) {
            return
        }
        animators.forEach {
            newRateMinsPerSec?.also { _ -> it?.rateMinsPerSec = newRateMinsPerSec }
            newFreezeTimeMillis?.also { _ -> it?.freezeTimeMillis = newFreezeTimeMillis }
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

    override fun onStartTrackingTouch(seekBar: SeekBar) {
        if (animators.any { it.hasSeekBar(seekBar) }) {
            stop()
        }
    }

    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        if (fromUser) launch(UI) {
            animators.find { it.hasSeekBar(seekBar) }?.seekTo(progress)
        }
    }

    override fun onStopTrackingTouch(seekBar: SeekBar) {
        val fullScreenAnimator = animators.find { it.hasSeekBar(seekBar) }
        val plainAnimators = animators.filterNotNull().filter { it.hasSeekBar(null) }
        plainAnimators.forEach {
            fullScreenAnimator?.imgBundle?.animationProgress?.also { fullScreenProgress ->
                it.imgBundle.animationProgress = fullScreenProgress
            }
        }
        resume()
    }

    private fun GifAnimator?.hasSeekBar(seekBar: SeekBar?) = this?.imgBundle?.seekBar == seekBar
}

class GifAnimator(
        private val imgBundles: List<ImageBundle>,
        private val imgDesc: ImgDescriptor,
        gifData: ByteArray,
        private val isOffline: Boolean
) {
    var rateMinsPerSec: Int = 20
    var freezeTimeMillis: Int = 0
    val imgBundle: ImageBundle get() = imgBundles[imgDesc.index]

    private val frameDelayMillis get() =  1000 * imgDesc.minutesPerFrame / rateMinsPerSec
    private val bitmapProvider = BitmapFreelists()
    private val gifDecoder = StandardGifDecoder(bitmapProvider).apply { read(gifData) }
    private var timestamp = 0L
    private var currFrame: Bitmap? = null
    private var currFrameIndex = 0

    fun animate(): Job? {
        val frameCount = gifDecoder.frameCount
        currFrameIndex = toFrameIndex(imgBundle.animationProgress)
        return launch(UI) {
            updateAgeText()
            var frame = suspendDecodeFrame(currFrameIndex)
            (currFrameIndex until frameCount).forEach { i ->
                val animationProgress = 100 * i / (frameCount - 1)
                if (i == currFrameIndex) {
                    showFrame(frame, animationProgress)
                }
                val frameShownAt = System.nanoTime()
                imgBundle.seekBar?.progress = animationProgress
                val lastFrameShown = i == frameCount - 1
                if (!lastFrameShown) {
                    currFrameIndex = i + 1
                    frame = suspendDecodeFrame(i + 1)
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
            imgBundle.animationProgress = 0
        }
    }

    suspend fun seekTo(animationProgress: Int) {
        val frameIndex = toFrameIndex(animationProgress)
        if (currFrameIndex == frameIndex) {
            return
        }
        currFrameIndex = frameIndex
        val newFrame = suspendDecodeFrame(frameIndex)
        if (currFrameIndex == frameIndex) {
            showFrame(newFrame, animationProgress)
        }
    }

    private fun showFrame(newFrame: Bitmap, animationProgress: Int) {
        imgBundle.animationProgress = animationProgress
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

    private fun toFrameIndex(animationProgress: Int) =
            (animationProgress / 100f * (gifDecoder.frameCount - 1)).roundToInt()
}
