package com.belotron.weatherradarhr

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.PropertyValuesHolder
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Matrix.MSCALE_X
import android.graphics.Matrix.MSCALE_Y
import android.graphics.Matrix.MTRANS_X
import android.graphics.Matrix.MTRANS_Y
import android.graphics.Point
import android.graphics.PointF
import android.os.Bundle
import android.os.Parcelable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_POINTER_UP
import android.view.MotionEvent.ACTION_UP
import android.view.ScaleGestureDetector
import android.view.ScaleGestureDetector.SimpleOnScaleGestureListener
import android.view.View
import android.view.View.MeasureSpec.AT_MOST
import android.view.View.MeasureSpec.EXACTLY
import android.view.View.MeasureSpec.UNSPECIFIED
import android.widget.ImageView
import android.widget.ImageView.ScaleType.FIT_END
import android.widget.ImageView.ScaleType.FIT_START
import android.widget.ImageView.ScaleType.MATRIX
import android.widget.OverScroller
import com.belotron.weatherradarhr.State.ANIMATE_ZOOM
import com.belotron.weatherradarhr.State.DRAG
import com.belotron.weatherradarhr.State.FLING
import com.belotron.weatherradarhr.State.NONE
import com.belotron.weatherradarhr.State.ZOOM
import kotlinx.coroutines.experimental.CancellableContinuation
import kotlinx.coroutines.experimental.CancellationException
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.suspendCancellableCoroutine
import kotlin.coroutines.experimental.suspendCoroutine
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val ZOOM_DURATION = 300L
private const val SUPER_MIN_MULTIPLIER = .75f
private const val SUPER_MAX_MULTIPLIER = 1.25f
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 128f

private const val STATE_IMAGEVIEW = "image_view"
private const val STATE_SCALE = "scale"
private const val STATE_IMG_FOCUS_X = "img_focus_x"
private const val STATE_IMG_FOCUS_Y = "img_focus_y"

private val quadratic = TimeInterpolator { t ->
    if (t <= 0.5) 2 * t * t
    else (1 - 2 * (1 - t) * (1 - t))
}

private enum class State {
    NONE, DRAG, ZOOM, FLING, ANIMATE_ZOOM
}


class TouchImageView : ImageView {

    private val overdrag = resources.getDimensionPixelOffset(R.dimen.overdrag)

    // Reusable value containers
    private val mx = Matrix()
    private val m = FloatArray(9)
    private val pointF = PointF()
    private val point = Point()

    private var state = NONE
        set(value) {
            info { "state: $value" }
            field = value
        }

    private var unitScale = 0f
    private var superMinScale = 0f
    private var superMaxScale = 0f
    private var currentZoom: Float = 1f

    private lateinit var scaleType: ImageView.ScaleType

    // Initial coordinates of the image inside the view,
    // when entering the full screen view
    private var fromImgX: Int = 0
    private var fromImgY: Int = 0

    // Current size of our view
    private var viewWidth = 0
    private var viewHeight = 0

    private var restoredInstanceState: Bundle? = null

    private var bitmapMeasured = false
    private var bitmapMeasuredContinuation: CancellableContinuation<Unit>? = null

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector
    private var flingJob: Job? = null
    private var userTouchListener: View.OnTouchListener? = null
    private var doubleTapListener: GestureDetector.OnDoubleTapListener? = null

    private fun imageSize(outParam: PointF): PointF {
        val (bitmapW, bitmapH) = bitmapSize(outParam) ?: return outParam.apply { set(0f, 0f) }
        val scale = unitScale * currentZoom
        return outParam.apply { set(bitmapW * scale, bitmapH * scale) }
    }

    constructor(context: Context) : this(context, null, 0)

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle) {
        isClickable = true
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, GestureListener())
        superMinScale = SUPER_MIN_MULTIPLIER * MIN_ZOOM
        superMaxScale = SUPER_MAX_MULTIPLIER * MAX_ZOOM
        setScaleType(MATRIX)
        super.setOnTouchListener(PrivateOnTouchListener())
    }

    override fun setScaleType(type: ImageView.ScaleType) {
        if (type == FIT_START || type == FIT_END) {
            throw UnsupportedOperationException("TouchImageView does not support FIT_START or FIT_END")
        }
        if (type == MATRIX) {
            super.setScaleType(MATRIX)
        } else {
            scaleType = type
        }
    }

    override fun getScaleType(): ImageView.ScaleType = scaleType

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val (bitmapW, bitmapH) = bitmapSize(point) ?: run {
            setMeasuredDimension(0, 0)
            return
        }
        val widthSize = View.MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = View.MeasureSpec.getMode(widthMeasureSpec)
        val heightSize = View.MeasureSpec.getSize(heightMeasureSpec)
        val heightMode = View.MeasureSpec.getMode(heightMeasureSpec)
        viewWidth = computeViewSize(widthMode, widthSize, bitmapW)
        viewHeight = computeViewSize(heightMode, heightSize, bitmapH)
        setMeasuredDimension(viewWidth, viewHeight)
    }

    override fun onDraw(canvas: Canvas) {
        if (!bitmapMeasured) {
            bitmapMeasured = tryMeasureBitmap()
        }
        if (bitmapMeasured) {
            bitmapMeasuredContinuation?.apply {
                bitmapMeasuredContinuation = null
                UI.resumeUndispatched(Unit)
            }
        }
        super.onDraw(canvas)
    }

    override fun onSaveInstanceState(): Parcelable {
        if (!bitmapMeasured) {
            return super.onSaveInstanceState()
        }
        return Bundle().apply {
            putParcelable(STATE_IMAGEVIEW, super.onSaveInstanceState())
            putFloat(STATE_SCALE, currentZoom * unitScale)
            loadMatrix()
            putFloat(STATE_IMG_FOCUS_X, 0.5f * viewWidth - m[MTRANS_X])
            putFloat(STATE_IMG_FOCUS_Y, 0.5f * viewHeight - m[MTRANS_Y])
        }
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        info { "restore instance state" }
        if (state !is Bundle) {
            super.onRestoreInstanceState(state)
        } else with (state) {
            super.onRestoreInstanceState(getParcelable(STATE_IMAGEVIEW))
            putParcelable(STATE_IMAGEVIEW, null)
            restoredInstanceState = this
        }
    }

    override fun setOnTouchListener(l: View.OnTouchListener) {
        userTouchListener = l
    }

    /**
     * startImgX,Y say where on the screen should the image be at the start of
     * zoom animation.
     * bitmapX,Y are the coordinates of the double-tap event in bitmap's
     * coordinate system.
     */
    suspend fun animateZoomEnter(startImgX: Int, startImgY: Int, bitmapFocusX: Float, bitmapFocusY: Float) {
        val (bitmapW, bitmapH) = bitmapSize(pointF) ?: return

        fun targetImgCoord(viewSize: Int, bitmapSize: Float, bitmapFocus: Float, targetScale: Float): Float {
            val imgSize = bitmapSize * targetScale
            val desiredImgCoord = viewSize / 2f - bitmapFocus * targetScale
            val coordAligningOppositeEdgeWithView = viewSize - bitmapSize * targetScale
            return if (imgSize > viewSize)
                min(0f, max(coordAligningOppositeEdgeWithView, desiredImgCoord)) else
                max(0f, min(coordAligningOppositeEdgeWithView, desiredImgCoord))
        }

        val (screenX, screenY) = IntArray(2).also { getLocationInWindow(it) }
        loadMatrix()
        val fitWidthScale = viewWidth.toFloat() / bitmapW
        val toScale = max(fitWidthScale, viewHeight.toFloat() / bitmapH)
        fromImgX = startImgX - screenX
        fromImgY = startImgY - screenY
        zoomAnimator(fitWidthScale, toScale,
                fromImgX.toFloat(), targetImgCoord(viewWidth, bitmapW, bitmapFocusX, toScale),
                fromImgY.toFloat(), targetImgCoord(viewHeight, bitmapH, bitmapFocusY, toScale))
            .run()

    }

    suspend fun animateZoomExit() {
        loadMatrix()
        val initialScale = m[MSCALE_X]
        val targetScale = (viewWidth.toFloat() / drawable!!.intrinsicWidth)
        val initialTransX = m[MTRANS_X]
        val initialTransY = m[MTRANS_Y]
        val targetTransX = fromImgX.toFloat()
        val targetTransY = fromImgY.toFloat()
        zoomAnimator(initialScale, targetScale, initialTransX, targetTransX, initialTransY, targetTransY).run()
    }

    suspend fun awaitBitmapMeasured() {
        if (bitmapMeasured) return
        require(bitmapMeasuredContinuation == null) { "Dangling bitmapMeasuredContinuation" }
        suspendCancellableCoroutine<Unit> { bitmapMeasuredContinuation = it }
    }

    fun setOnDoubleTapListener(l: GestureDetector.OnDoubleTapListener) {
        doubleTapListener = l
    }

    fun reset() {
        bitmapMeasured = false
    }

    private fun tryMeasureBitmap(): Boolean {
        val (bitmapW, bitmapH) = bitmapSize(point) ?: return false
        unitScale = min(viewWidth.toFloat() / bitmapW, viewHeight.toFloat() / bitmapH)
        loadMatrix()
        val state = restoredInstanceState
        if (state == null) {
            // No restored state to apply -- assume we're here for the first time after
            // a bitmap has been set on the view
            mx.setScale(unitScale, unitScale)
            imageMatrix = mx
            currentZoom = 1f
            return true
        }
        restoredInstanceState = null

        // Apply the restored instance state after a configuration change.
        // Restore the scaling factor and position the image so the same point
        // is in the center of the view as it was before the change.

        val restoredScale = state.getFloat(STATE_SCALE)
        m[MSCALE_X] = restoredScale
        m[MSCALE_Y] = restoredScale
        currentZoom = restoredScale / unitScale
        val viewCenterX = 0.5f * viewWidth
        val viewCenterY = 0.5f * viewHeight
        m[MTRANS_X] = viewCenterX - state.getFloat(STATE_IMG_FOCUS_X)
        m[MTRANS_Y] = viewCenterY - state.getFloat(STATE_IMG_FOCUS_Y)
        applyMatrix()
        springBackZoomAndTrans(viewCenterX, viewCenterY)
        return true
    }

    private fun zoomAnimator(
            fromScale: Float, toScale: Float,
            fromTransX: Float, toTransX: Float,
            fromTransY: Float, toTransY: Float
    ): ValueAnimator {
        return ValueAnimator.ofPropertyValuesHolder(
                PropertyValuesHolder.ofFloat("scale", fromScale, toScale),
                PropertyValuesHolder.ofFloat("transX", fromTransX, toTransX),
                PropertyValuesHolder.ofFloat("transY", fromTransY, toTransY)
        ).apply {
            duration = ZOOM_DURATION
            interpolator = quadratic
            addUpdateListener { anim ->
                loadMatrix()
                val scale = anim.getAnimatedValue("scale") as Float
                val transX = anim.getAnimatedValue("transX") as Float
                val transY = anim.getAnimatedValue("transY") as Float
                loadMatrix()
                m[MSCALE_X] = scale
                m[MSCALE_Y] = scale
                m[MTRANS_X] = transX
                m[MTRANS_Y] = transY
                applyMatrix()
                currentZoom = scale / unitScale
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator?) {
                    state = ANIMATE_ZOOM
                }
                override fun onAnimationEnd(a: Animator?) = resetState()
                override fun onAnimationCancel(a: Animator?) = resetState()
                private fun resetState() {
                    if (state == ANIMATE_ZOOM) {
                        state = NONE
                    }
                }
            })
        }
    }

    private fun startFling(velocityX: Float, velocityY: Float) {
        val oldFling = flingJob
        val context = context
        oldFling?.cancel()
        flingJob = start {
            withState(FLING) {
                loadMatrix()
                val (imageWidth, imageHeight) = imageSize(pointF)
                val (minTransX, maxTransX) = transBounds(viewWidth, imageWidth, 0f, pointF)
                val (minTransY, maxTransY) = transBounds(viewHeight, imageHeight, 0f, pointF)
                val scroller = OverScroller(context).apply {
                    fling(m[MTRANS_X].toInt(), m[MTRANS_Y].toInt(),
                            velocityX.roundToInt(), velocityY.roundToInt(),
                            minTransX.roundToInt(), maxTransX.roundToInt(),
                            minTransY.roundToInt(), maxTransY.roundToInt(),
                            overdrag, overdrag)
                }
                while (scroller.computeScrollOffset()) {
                    loadMatrix()
                    m[MTRANS_X] = scroller.currX.toFloat()
                    m[MTRANS_Y] = scroller.currY.toFloat()
                    applyMatrix()
                    UI.awaitFrame()
                }
            }
        }
    }

    private fun constrainTransAndPushMatrix() {
        mx.getValues(m)
        val (imageWidth, imageHeight) = imageSize(pointF)
        val (constrainedTransX, constrainedTransY) =
                constrainedTrans(true, m[MTRANS_X], m[MTRANS_Y], imageWidth, imageHeight, pointF)
        m[MTRANS_X] = constrainedTransX
        m[MTRANS_Y] = constrainedTransY
        applyMatrix()
    }

    /**
     * Operates on [mx], which the caller must initialize to the current image matrix.
     */
    private fun springBackZoomAndTrans(focusX: Float, focusY: Float) {
        val targetZoom = coerceToRange(currentZoom, pointF.apply { set(MIN_ZOOM, MAX_ZOOM) })
        mx.getValues(m)
        val currTransX = m[MTRANS_X]
        val currTransY = m[MTRANS_Y]
        val (constrainedTransX, constrainedTransY) = run {
            mx.postScale(targetZoom / currentZoom, targetZoom / currentZoom, focusX, focusY)
            mx.getValues(m)
            val targetTransX = m[MTRANS_X]
            val targetTransY = m[MTRANS_Y]
            val (bitmapW, bitmapH) = bitmapSize(pointF) ?: return
            val targetImgW = bitmapW * targetZoom * unitScale
            val targetImgH = bitmapH * targetZoom * unitScale
            constrainedTrans(false, targetTransX, targetTransY, targetImgW, targetImgH, pointF)
        }
        if (targetZoom != currentZoom || constrainedTransX != currTransX || constrainedTransY != currTransY) {
            zoomAnimator(currentZoom * unitScale, targetZoom * unitScale,
                    currTransX, constrainedTransX,
                    currTransY, constrainedTransY)
                .start()
        }
    }

    /**
     * This function deals with dimensions along a single axis.
     *
     * Given the size of the image and the view, it computes the min and max
     * allowed translation so that either the entire span of the image stays
     * within the view or the entire span of the view stays within the image
     * (depending on which of the two is larger). It extends this range by
     * the allowed "overdrag" on both sides.
     *
     * Populates the provided [outParam] with (minAllowed, maxAllowed)
     * translation and returns it.
     */
    private fun transBounds(viewSize: Int, imgSize: Float, overdrag: Float, outParam: PointF): PointF {
        val transToAlignRightEdgeViewAndScreen = viewSize - imgSize
        return outParam.apply {
            if (transToAlignRightEdgeViewAndScreen >= 0f) {
                set(-overdrag, transToAlignRightEdgeViewAndScreen + overdrag)
            } else {
                set(transToAlignRightEdgeViewAndScreen - overdrag, overdrag)
            }
        }
    }

    private fun constrainedTrans(
            allowOverdrag: Boolean, currTransX: Float, currTransY: Float,
            imageWidth: Float, imageHeight: Float, dest: PointF
    ): PointF {
        val overdrag = if (allowOverdrag) this.overdrag.toFloat() else 0f
        return dest.apply { set(
                coerceToRange(currTransX, transBounds(viewWidth, imageWidth, overdrag, pointF)),
                coerceToRange(currTransY, transBounds(viewHeight, imageHeight, overdrag, pointF))
        ) }
    }

    private fun loadMatrix() {
        mx.set(imageMatrix)
        mx.getValues(m)
    }

    private fun applyMatrix() {
        mx.setValues(m)
        imageMatrix = mx
    }

    private suspend fun withState(state: State, block: suspend () -> Unit) {
        try {
            this.state = state
            block()
            this.state = NONE
        } catch (e: CancellationException) {
            // The cancelling code already set another state, don't reset it
        } catch (e: Throwable) {
            if (this.state == state) {
                this.state = NONE
            }
        }
    }

    private inner class GestureListener : SimpleOnGestureListener() {

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            return doubleTapListener?.onSingleTapConfirmed(e) ?: performClick()
        }

        override fun onLongPress(e: MotionEvent) {
            performLongClick()
        }

        override fun onFling(e1: MotionEvent, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (state != ANIMATE_ZOOM) {
                startFling(velocityX, velocityY)
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            return doubleTapListener != null && doubleTapListener!!.onDoubleTap(e)
        }

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            return doubleTapListener != null && doubleTapListener!!.onDoubleTapEvent(e)
        }
    }

    private inner class PrivateOnTouchListener : View.OnTouchListener {

        private val initial = PointF()
        private val initialMatrix = Matrix()

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            if (state == NONE || state == DRAG || state == FLING) {
                when (event.action) {
                    ACTION_DOWN -> {
                        initial.set(event.x, event.y)
                        initialMatrix.set(imageMatrix)
                        flingJob?.cancel()
                        state = DRAG
                    }
                    ACTION_MOVE -> if (state == DRAG) {
                        val deltaX = event.x - initial.x
                        val deltaY = event.y - initial.y
                        mx.set(initialMatrix)
                        mx.postTranslate(deltaX, deltaY)
                        constrainTransAndPushMatrix()
                    }
                    ACTION_UP, ACTION_POINTER_UP -> if (state == DRAG) {
                        val (imageWidth, imageHeight) = imageSize(pointF)
                        val (minTransX, maxTransX) = transBounds(viewWidth, imageWidth, 0f, pointF)
                        val (minTransY, maxTransY) = transBounds(viewHeight, imageHeight, 0f, pointF)
                        loadMatrix()
                        if (m[MTRANS_X] !in minTransX..maxTransX || m[MTRANS_Y] !in minTransY..maxTransY) {
                            startFling(0f, 0f)
                        } else {
                            state = NONE
                        }
                    }
                }
            }
            if (userTouchListener != null) {
                userTouchListener!!.onTouch(v, event)
            }
            return true
        }
    }

    private inner class ScaleListener : SimpleOnScaleGestureListener() {
        val initialMatrix = Matrix()
        val initialFocus = PointF()
        val latestFocus = PointF()
        var initialSpan = 1f
        var initialZoom = 1f

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            state = ZOOM
            initialMatrix.set(imageMatrix)
            initialFocus.set(detector.focusX, detector.focusY)
            initialSpan = detector.currentSpan
            initialZoom = currentZoom
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val (initFocusX, initFocusY) = initialFocus
            latestFocus.set(detector.focusX, detector.focusY)
            val scale = detector.currentSpan / initialSpan
            currentZoom = initialZoom * scale
            mx.set(initialMatrix)
            mx.postTranslate(detector.focusX - initFocusX, detector.focusY - initFocusY)
            mx.postScale(scale, scale, detector.focusX, detector.focusY)
            constrainTransAndPushMatrix()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            super.onScaleEnd(detector)
            if (state == ZOOM) {
                state = NONE
            }
            mx.set(imageMatrix)
            val (latestFocusX, latestFocusY) = latestFocus
            springBackZoomAndTrans(latestFocusX, latestFocusY)
        }
    }
}

private fun coerceToRange(x: Float, range: PointF): Float = range.let {
    (rangeStart, rangeEnd) -> x.coerceIn(rangeStart, rangeEnd)
}

private fun computeViewSize(mode: Int, requestedSize: Int, drawableSize: Int): Int {
    return when (mode) {
        AT_MOST -> Math.min(drawableSize, requestedSize)
        UNSPECIFIED -> drawableSize
        EXACTLY -> requestedSize
        else -> throw IllegalArgumentException("Undefined measure specification mode $mode")
    }
}

private suspend fun ValueAnimator.run() {
    suspendCoroutine<Unit> { cont ->
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                cont.resume(Unit)
            }
        })
        start()
    }
}
