package com.belotron.weatherradarhr

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import com.belotron.weatherradarhr.gifdecode.BitmapFreelists
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy
import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val singleThread = ThreadPoolExecutor(0, 1, 2, SECONDS, ArrayBlockingQueue(1),
    { task -> Thread(task, "weather-radar-animation") }, DiscardOldestPolicy())
        .asCoroutineDispatcher()

private val linear = LinearInterpolator()

private lateinit var dateFormat: DateFormat
private lateinit var timeFormat: DateFormat

class AnimationLooper(
        private val ds: DisplayState
) : SeekBar.OnSeekBarChangeListener {

    private val animators = arrayOfNulls<FrameAnimator>(ds.imgBundles.size)
    private val animatorJobs = arrayOfNulls<Job>(ds.imgBundles.size)
    private var loopingJob: Job? = null

    fun receiveNewFrames(desc: FrameSequenceLoader, frameSequence: FrameSequence<out Frame>, isOffline: Boolean) {
        animators[desc.index] = FrameAnimator(ds.imgBundles, desc, frameSequence, isOffline)
    }

    fun resume(context: Context? = null, newRateMinsPerSec: Int? = null, newFreezeTimeMillis: Int? = null) {
        info { "AnimationLooper.resume" }
        context?.also {
            dateFormat = it.dateFormat
            timeFormat = it.timeFormat
        }
        if (animators.all { it == null }) {
            return
        }
        animators.filterNotNull().forEach { animator ->
            newRateMinsPerSec?.also { animator.rateMinsPerSec = it }
            newFreezeTimeMillis?.also { animator.freezeTimeMillis = it }
        }
        if (ds.isTrackingTouch) {
            return
        }
        stop()
        var oldLoopingJob = loopingJob
        loopingJob = appCoroScope.start {
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
            ds.isTrackingTouch = true
            stop()
        }
    }

    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        if (fromUser) ds.start {
            animators.find { it.hasSeekBar(seekBar) }?.seekTo(progress, seekBar.context)
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
            ds.isTrackingTouch = false
            resume()
        }
    }

    private fun FrameAnimator?.hasSeekBar(seekBar: SeekBar?) = this?.imgBundle?.seekBar == seekBar
}

class FrameAnimator(
    private val imgBundles: List<ImageBundle>,
    private val frameSequenceDesc: FrameSequenceLoader,
    frameSequence: FrameSequence<out Frame>,
    private val isOffline: Boolean
) {
    var rateMinsPerSec: Int = 20
    var freezeTimeMillis: Int = 0
    val imgBundle: ImageBundle get() = imgBundles[frameSequenceDesc.index]

    private val frameDelayMillis get() =  1000 * frameSequenceDesc.minutesPerFrame / rateMinsPerSec
    private val allocator = BitmapFreelists()
    private val frameDecoder = frameSequence.intoDecoder(allocator)
    private var currFrame: Bitmap? = null
    private var currFrameIndex = 0
    private var seekBarAnimator: ObjectAnimator? = null

    fun animate(isFullRange: Boolean): Job {
        val frameCount = frameDecoder.frameCount
        val startFrameIndex = if (isFullRange) 0 else max(0, frameCount - frameSequenceDesc.framesToKeep)
        currFrameIndex = toFrameIndex(imgBundle.animationProgress, startFrameIndex)
        return appCoroScope.start {
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
                        if (lastFrameShown) max(freezeTimeMillis, frameDelayMillis)
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

    suspend fun seekTo(animationProgress: Int, ctx: Context) {
        val targetIndex = toFrameIndex(animationProgress, 0)
        if (targetIndex == currFrameIndex) {
            return
        }
        currFrameIndex = targetIndex
        updateSeekBarThumb(targetIndex, timestamp(targetIndex))
        if (targetIndex == 0 || targetIndex == frameDecoder.frameCount - 1) {
            val vibrator = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            } else {
                vibrator.vibrate(20)
            }
        }
        val newFrame = suspendDecodeFrame(targetIndex, singleThread)
        showFrame(newFrame, animationProgress)
        updateAgeText()
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
            thumbText = timeFormat.format(timestamp)
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
                duration = (frameDecoder.frameCount * frameDelayMillis * (1 - progressF)).roundToLong()
                interpolator = linear
                start()
            }
        }
    }

    private fun updateAgeText() {
        imgBundle.takeIf { it.status in ImageBundle.loadingOrShowing }?.textView?.setAgeText(
                timestamp(frameDecoder.frameCount - 1), isOffline, dateFormat = dateFormat, timeFormat = timeFormat)
    }

    private fun timestamp(frameIndex: Int) = frameDecoder.sequence.frames[frameIndex].timestamp

    private suspend fun suspendDecodeFrame(frameIndex: Int, coroCtx: CoroutineDispatcher = IO) =
            withContext(coroCtx) {
                synchronized (frameDecoder) {
                    frameDecoder.decodeFrame(frameIndex)
                }
            }

    private fun Bitmap.dispose() = allocator.release(this)

    private fun toFrameIndex(animationProgress: Int, startFrameIndex: Int) =
            (animationProgress / 100f * (frameDecoder.frameCount - startFrameIndex - 1) + startFrameIndex)
                    .roundToInt()

    private fun toProgress(frameIndex: Int) = frameDecoder.frameCount.let { frameCount ->
        if (frameCount == 1) 100
        else 100 * frameIndex / (frameCount - 1)
    }
}
