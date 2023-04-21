/*
 * Copyright (C) 2018-2023 Marko Topolnik
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.belotron.weatherradarhr

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import androidx.lifecycle.viewModelScope
import com.belotron.weatherradarhr.gifdecode.BitmapFreelists
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        private val vmodel: MainFragmentModel
) : SeekBar.OnSeekBarChangeListener {

    private val animators = arrayOfNulls<FrameAnimator>(vmodel.imgBundles.size)
    private val animatorJobs = arrayOfNulls<Job>(vmodel.imgBundles.size)
    private var loopingJob: Job? = null

    fun receiveNewFrames(
        radarName: String, positionInUI: Int,
        loader: FrameSequenceLoader, frameSequence: FrameSequence<out Frame>
    ) {
        animators[positionInUI]?.dispose()
        animators[positionInUI] = FrameAnimator(radarName, positionInUI, loader, vmodel, frameSequence)
    }

    fun resume(context: Context? = null, newAnimationCoversMinutes: Int? = null,
               newRateMinsPerSec: Int? = null, newFreezeTimeMillis: Int? = null,
               newSeekbarVibrate: Boolean? = null
    ) {
        info { "AnimationLooper.resume" }
        context?.also {
            dateFormat = it.dateFormat
            timeFormat = it.timeFormat
        }
        if (animators.all { it == null }) {
            return
        }
        animators.filterNotNull().forEach { animator ->
            newAnimationCoversMinutes?.also { animator.animationCoversMinutes = it }
            newRateMinsPerSec?.also { animator.rateMinsPerSec = it }
            newFreezeTimeMillis?.also { animator.freezeTimeMillis = it }
            newSeekbarVibrate?.also { animator.seekbarVibrate = it }
        }
        if (vmodel.isTrackingTouch) {
            return
        }
        stop()
        var oldLoopingJob = loopingJob
        loopingJob = vmodel.viewModelScope.launch {
            oldLoopingJob?.join()
            oldLoopingJob = null
            while (true) {
                animatorJobs.forEach { it?.join() }
                animators.withIndex()
                        .filter { (i, _) -> vmodel.indexOfImgInFullScreen?.let { it == i } ?: true }
                        .forEach { (i, it) -> animatorJobs[i] = it?.animate() }
            }
        }
    }

    fun stop() {
        loopingJob?.cancel()
        animatorJobs.forEach { it?.cancel() }
        animators.forEach { it?.stopSeekBarAnimation() }
    }

    fun dispose() {
        stop()
        animators.filterNotNull().forEach { it.dispose() }
    }

    override fun onStartTrackingTouch(seekBar: SeekBar) {
        if (animators.any { it.hasSeekBar(seekBar) }) {
            vmodel.isTrackingTouch = true
            stop()
        }
    }

    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        if (fromUser) vmodel.viewModelScope.launch {
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
            vmodel.isTrackingTouch = false
            resume()
        }
    }

    private fun FrameAnimator?.hasSeekBar(seekBar: SeekBar?) = this?.imgBundle?.seekBar == seekBar
}

class FrameAnimator(
    private val radarName: String,
    private val positionInUI: Int,
    private val frameSeqLoader: FrameSequenceLoader,
    vmodel: MainFragmentModel,
    frameSequence: FrameSequence<out Frame>,
) {
    var animationCoversMinutes = 1
    var rateMinsPerSec = 20
    var freezeTimeMillis = 500
    var seekbarVibrate = true
    val imgBundle: ImageBundle get() = imgBundles[positionInUI]

    private val viewModelScope = vmodel.viewModelScope
    private val imgBundles = vmodel.imgBundles
    private val frameDelayMillis get() =  1000 * frameSeqLoader.minutesPerFrame / rateMinsPerSec
    private val allocator = BitmapFreelists()
    private val frameDecoder = frameSequence.intoDecoder(allocator)
    private var currFrame: Bitmap? = null
    private var currFrameIndex = 0
    private var seekBarAnimator: ObjectAnimator? = null

    private fun correctFrameCount() = frameSeqLoader.correctFrameCount(animationCoversMinutes)

    fun animate(): Job {
        currFrameIndex = toFrameIndex(imgBundle.animationProgress)
        return viewModelScope.launch {
            updateAgeText()
            var frame = suspendDecodeFrame(currFrameIndex)
            val correctFrameCount = correctFrameCount()
            (currFrameIndex until correctFrameCount).forEach { i ->
                val animationProgress = toProgress(frameIndex = i)
                if (i == currFrameIndex) {
                    showFrame(frame, animationProgress)
                }
                animateSeekBarIfNeeded()
                val frameShownAt = System.nanoTime()
                val lastFrameShown = i == correctFrameCount - 1
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
            it.cancel()
            seekBarAnimator = null
        }
    }

    suspend fun seekTo(animationProgress: Int, ctx: Context) {
        val targetIndex = toFrameIndex(animationProgress)
        if (targetIndex == currFrameIndex) {
            return
        }
        currFrameIndex = targetIndex
        updateSeekBarThumb(targetIndex, timestamp(targetIndex))
        if (seekbarVibrate && (targetIndex == 0 || targetIndex == correctFrameCount() - 1)) {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(20)
            }
        }
        val newFrame = suspendDecodeFrame(targetIndex, singleThread)
        showFrame(newFrame, animationProgress)
        updateAgeText()
    }

    fun dispose() {
        allocator.dispose()
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
                duration = (correctFrameCount() * frameDelayMillis * (1 - progressF)).roundToLong()
                interpolator = linear
                start()
            }
        }
    }

    private fun updateAgeText() {
        imgBundle.takeIf { it.status in ImageBundle.loadingOrShowing }?.textView?.setAgeText(
            radarName,
            timestamp(correctFrameCount() - 1), dateFormat = dateFormat, timeFormat = timeFormat)
    }

    private fun timestamp(correctFrameIndex: Int) =
            frameDecoder.sequence.frames[adjustedFrameIndex(correctFrameIndex)].timestamp

    private suspend fun suspendDecodeFrame(correctFrameIndex: Int, coroCtx: CoroutineDispatcher = IO) =
            withContext(coroCtx) {
                synchronized (frameDecoder) {
                    frameDecoder.getBitmap(adjustedFrameIndex(correctFrameIndex))
                }
            }

    private fun Bitmap.dispose() = allocator.release(this)

    private fun toFrameIndex(animationProgress: Int): Int {
        return (animationProgress / 100f * (correctFrameCount() - 1))
                .roundToInt()
    }

    private fun adjustedFrameIndex(correctFrameIndex: Int): Int {
        return max(0, correctFrameIndex - (correctFrameCount() - frameDecoder.frameCount))
    }

    private fun toProgress(frameIndex: Int) = correctFrameCount().let { frameCount ->
        if (frameCount == 1) 100
        else 100 * frameIndex / (frameCount - 1)
    }
}
