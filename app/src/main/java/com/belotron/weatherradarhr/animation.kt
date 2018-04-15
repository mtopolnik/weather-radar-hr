package com.belotron.weatherradarhr

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.text.format.DateFormat.getTimeFormat
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import com.belotron.weatherradarhr.ImageBundle.Status.SHOWING
import com.belotron.weatherradarhr.gifdecode.BitmapFreelists
import com.belotron.weatherradarhr.gifdecode.GifDecoder
import com.belotron.weatherradarhr.gifdecode.ParsedGif
import kotlinx.coroutines.experimental.CoroutineDispatcher
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.asCoroutineDispatcher
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.withContext
import java.text.DateFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy
import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import android.text.format.DateFormat as AndroidDateFormat

private val singleThread = ThreadPoolExecutor(0, 1, 2, SECONDS, ArrayBlockingQueue(1),
        ThreadFactory { task -> Thread(task, "weather-radar-animation") }, DiscardOldestPolicy())
        .asCoroutineDispatcher()

private val linear = LinearInterpolator()

lateinit var thumbDateFormat: DateFormat

class AnimationLooper(
        private val ds: DisplayState
) : SeekBar.OnSeekBarChangeListener {

    private val animators = arrayOfNulls<GifAnimator>(ds.imgBundles.size)
    private val animatorJobs = arrayOfNulls<Job>(ds.imgBundles.size)
    private var loopingJob: Job? = null

    fun receiveNewGif(desc: ImgDescriptor, parsedGif: ParsedGif, isOffline: Boolean) {
        animators[desc.index] = GifAnimator(ds.imgBundles, desc, parsedGif, isOffline)
    }

    fun resume(context: Context? = null, newRateMinsPerSec: Int? = null, newFreezeTimeMillis: Int? = null) {
        info { "AnimationLooper.resume" }
        context?.also {
            thumbDateFormat = getTimeFormat(it)
        }
        if (animators.none()) {
            return
        }
        stop()
        animators.filterNotNull().forEach {
            newRateMinsPerSec?.also { _ -> it.rateMinsPerSec = newRateMinsPerSec }
            newFreezeTimeMillis?.also { _ -> it.freezeTimeMillis = newFreezeTimeMillis }
        }
        var oldLoopingJob = loopingJob
        loopingJob = start {
            oldLoopingJob?.join()
            oldLoopingJob = null
            while (true) {
                animatorJobs.forEach { it?.join() }
                animators.withIndex()
                        .filter { (i, _) -> ds.indexOfImgInFullScreen?.let { it == i } ?: true }
                        .forEach { (i, it) -> animatorJobs[i] = it?.animate(ds.isInFullScreen) }
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
        if (seekBar !is ThumbSeekBar) return
        (animators.find { it.hasSeekBar(seekBar) } ?: return).also { fullScreenAnimator ->
            seekBar.thumbText = ""
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
        private val parsedGif: ParsedGif,
        private val isOffline: Boolean
) {
    var rateMinsPerSec: Int = 20
    var freezeTimeMillis: Int = 0
    val imgBundle: ImageBundle get() = imgBundles[imgDesc.index]

    private val frameDelayMillis get() =  1000 * imgDesc.minutesPerFrame / rateMinsPerSec
    private val allocator = BitmapFreelists()
    private val gifDecoder = GifDecoder(allocator, parsedGif)
    private var currFrame: Bitmap? = null
    private var currFrameIndex = 0
    private var seekBarAnimator: ObjectAnimator? = null

    fun animate(isFullRange: Boolean): Job? {
        val frameCount = gifDecoder.frameCount
        val startFrameIndex = if (isFullRange) 0 else frameCount - imgDesc.framesToKeep
        currFrameIndex = toFrameIndex(imgBundle.animationProgress, startFrameIndex)
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
        toFrameIndex(animationProgress, 0).also { it ->
            if (it == currFrameIndex) {
                return
            }
            currFrameIndex = it
            updateSeekBarThumb(it, timestamp(it))
            val newFrame = suspendDecodeFrame(it, singleThread)
            showFrame(newFrame, animationProgress)
            updateAgeText()
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

    private fun updateAgeText() {
        imgBundle.takeIf { it.status == SHOWING }?.textView?.setAgeText(
                timestamp(parsedGif.frameCount - 1), isOffline)
    }

    private fun timestamp(frameIndex: Int) = parsedGif.frames[frameIndex].timestamp

    private suspend fun suspendDecodeFrame(frameIndex: Int, coroCtx: CoroutineDispatcher = threadPool) =
            withContext(coroCtx) {
                synchronized (gifDecoder) {
                    gifDecoder.decodeFrame(frameIndex).toBitmap()
                }
            }

    private fun Bitmap.dispose() = allocator.release(this)

    private fun toFrameIndex(animationProgress: Int, startFrameIndex: Int) =
            (animationProgress / 100f * (gifDecoder.frameCount - startFrameIndex - 1) + startFrameIndex)
                    .roundToInt()

    private fun toProgress(frameIndex: Int) = 100 * frameIndex / (gifDecoder.frameCount - 1)
}
