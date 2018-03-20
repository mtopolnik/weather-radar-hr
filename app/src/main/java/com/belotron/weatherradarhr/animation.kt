package com.belotron.weatherradarhr

import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import com.belotron.weatherradarhr.ImageBundle.Status.SHOWING
import com.belotron.weatherradarhr.gifdecode.BitmapFreelists
import com.belotron.weatherradarhr.gifdecode.StandardGifDecoder
import kotlinx.coroutines.experimental.CoroutineDispatcher
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.asCoroutineDispatcher
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy
import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val singleThread = ThreadPoolExecutor(0, 1, 2, SECONDS, ArrayBlockingQueue(1),
        ThreadFactory { task -> Thread(task, "weather-radar-animation") }, DiscardOldestPolicy())
        .asCoroutineDispatcher()

private val linear = LinearInterpolator()

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
        loopingJob = start {
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
        animators.forEach { it?.stopSeekBarAnimation() }
        loopingJob?.cancel()
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {
        if (animators.any { it.hasSeekBar(seekBar) }) {
            stop()
        }
    }

    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        if (fromUser) start {
            animators.find { it.hasSeekBar(seekBar) }?.seekTo(progress)
        }
    }

    override fun onStopTrackingTouch(seekBar: SeekBar) {
        val seekBar = seekBar as ThumbSeekBar
        (animators.find { it.hasSeekBar(seekBar) } ?: return).also { fullScreenAnimator ->
            seekBar.thumbText = ""
            seekBar.invalidate()
            val fullScreenProgress = fullScreenAnimator.imgBundle.animationProgress
            animators.filterNotNull().filter { it.hasSeekBar(null) }.forEach { plainAnimator ->
                plainAnimator.imgBundle.animationProgress = fullScreenProgress
            }
            resume()
        }
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
    private val timestamps = LongArray(gifDecoder.frameCount)
    private val thumbDateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var currFrame: Bitmap? = null
    private var currFrameIndex = 0
    private var seekBarAnimator: ObjectAnimator? = null

    fun animate(): Job? {
        val frameCount = gifDecoder.frameCount
        currFrameIndex = toFrameIndex(imgBundle.animationProgress)
        return start {
            updateAgeText()
            var frame = suspendDecodeFrame(currFrameIndex)
            (currFrameIndex until frameCount).forEach { i ->
                val animationProgress = toProgress(frameIndex = i)
                if (i == currFrameIndex) {
                    showFrame(frame, animationProgress)
                }
                animateSeekBarIfNeeded()
                val frameShownAt = System.nanoTime()
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
            stopSeekBarAnimation()
        }
    }

    fun stopSeekBarAnimation() {
        seekBarAnimator?.also {
            it.pause()
            seekBarAnimator = null
        }
    }

    suspend fun seekTo(animationProgress: Int) {
        val frameIndex = toFrameIndex(animationProgress)
        if (frameIndex == currFrameIndex) {
            return
        }
        currFrameIndex = frameIndex
        val thumbUpdated = timestamps[frameIndex].takeIf { it != 0L}
                ?.let { updateSeekBarThumb(frameIndex, it); true }
                ?: false
        val newFrame = suspendDecodeFrame(frameIndex, singleThread)
        showFrame(newFrame, animationProgress)
        updateAgeText()
        if (!thumbUpdated) {
            updateSeekBarThumb(frameIndex, getTimestamp(frameIndex, newFrame))
        }
    }

    private fun showFrame(newFrame: Bitmap, animationProgress: Int) {
        with (imgBundle) {
            imgView?.setImageBitmap(newFrame)
            this.animationProgress = animationProgress
        }
        currFrame?.dispose()
        currFrame = newFrame
    }

    private fun updateSeekBarThumb(frameIndex: Int, timestamp: Long) {
        imgBundle.seekBar?.apply {
            thumbProgress = toProgress(frameIndex)
            thumbText = thumbDateFormat.format(timestamp)
            invalidate()
        }

    }

    private fun animateSeekBarIfNeeded() {
        if (seekBarAnimator != null) {
            return
        }
        val seekBar = imgBundle.seekBar ?: return
        seekBar.also {
            val progressF = imgBundle.animationProgress / 100f
            seekBarAnimator = ObjectAnimator.ofInt(it, "progress", imgBundle.animationProgress, 100).apply {
                duration = (gifDecoder.frameCount * frameDelayMillis * (1 - progressF)).roundToLong()
                interpolator = linear
                start()
            }
        }
    }

    private suspend fun updateAgeText() {
        imgBundle.takeIf { it.status == SHOWING }?.textView?.setAgeText(
                suspendGetTimestamp(timestamps.size - 1), isOffline)
    }

    @Suppress("NOTHING_TO_INLINE")
    private suspend inline fun suspendGetTimestamp(frameIndex: Int): Long {
        if (timestamps[frameIndex] == 0L) {
            suspendDecodeFrame(frameIndex).also {
                timestamps[frameIndex] = imgDesc.ocrTimestamp(it)
                it.dispose()
            }
        }
        return timestamps[frameIndex]
    }

    private fun getTimestamp(frameIndex: Int, frame: Bitmap): Long {
        if (timestamps[frameIndex] == 0L) {
            timestamps[frameIndex] = imgDesc.ocrTimestamp(frame)
        }
        return timestamps[frameIndex]
    }

    private suspend fun suspendDecodeFrame(frameIndex: Int, coroCtx: CoroutineDispatcher = threadPool) =
            withContext(coroCtx) { gifDecoder.decodeFrame(frameIndex) }

    private fun Bitmap.dispose() = bitmapProvider.release(this)

    private fun toFrameIndex(animationProgress: Int) =
            (animationProgress / 100f * (gifDecoder.frameCount - 1)).roundToInt()

    private fun toProgress(frameIndex: Int) = 100 * frameIndex / (gifDecoder.frameCount - 1)
}
