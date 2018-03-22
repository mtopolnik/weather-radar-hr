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
private const val BLACK_BORDER_PROPORTION = 0.25f

private const val STATE_IMAGEVIEW = "image_view"
private const val STATE_SCALE = "scale"
private const val STATE_IMG_FOCUS_X = "img_focus_x"
private const val STATE_IMG_FOCUS_Y = "img_focus_y"

private val quadratic = TimeInterpolator { t ->
    if (t < 0.5) 2 * t * t
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

    private var matrixInitialized = false
    private var onMatrixInitializedContinuation: CancellableContinuation<Unit>? = null

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
        if (!matrixInitialized) {
            matrixInitialized = tryInitializeMatrix()
        }
        if (matrixInitialized) {
            onMatrixInitializedContinuation?.apply {
                onMatrixInitializedContinuation = null
                UI.resumeUndispatched(Unit)
            }
        }
        super.onDraw(canvas)
    }

    override fun onSaveInstanceState(): Parcelable {
        return Bundle().apply {
            putParcelable(STATE_IMAGEVIEW, super.onSaveInstanceState())
            putFloat(STATE_SCALE, currentZoom * unitScale)
            loadMatrix()
            putFloat(STATE_IMG_FOCUS_X, 0.5f * viewWidth - m[MTRANS_X])
            putFloat(STATE_IMG_FOCUS_Y, 0.5f * viewHeight - m[MTRANS_Y])
        }
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        info {"restore instance state"}
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
        animateZoom(
                fitWidthScale, toScale,
                fromImgX.toFloat(), targetImgCoord(viewWidth, bitmapW, bitmapFocusX, toScale),
                fromImgY.toFloat(), targetImgCoord(viewHeight, bitmapH, bitmapFocusY, toScale))

    }

    suspend fun animateZoomExit() {
        loadMatrix()
        val initialScale = m[MSCALE_X]
        val targetScale = (viewWidth.toFloat() / drawable!!.intrinsicWidth)
        val initialTransX = m[MTRANS_X]
        val initialTransY = m[MTRANS_Y]
        val targetTransX = fromImgX.toFloat()
        val targetTransY = fromImgY.toFloat()
        animateZoom(initialScale, targetScale, initialTransX, targetTransX, initialTransY, targetTransY)
    }

    suspend fun awaitMatrixInitialized() {
        if (matrixInitialized) return
        require(onMatrixInitializedContinuation == null) { "Dangling onMatrixInitializedContinuation" }
        suspendCancellableCoroutine<Unit> { onMatrixInitializedContinuation = it }
    }

    fun setOnDoubleTapListener(l: GestureDetector.OnDoubleTapListener) {
        doubleTapListener = l
    }

    fun reset() {
        matrixInitialized = false
    }

    private fun tryInitializeMatrix(): Boolean {
        val (bitmapW, bitmapH) = bitmapSize(point) ?: run {
            return false
        }
        unitScale = min(viewWidth.toFloat() / bitmapW, viewHeight.toFloat() / bitmapH)
        loadMatrix()
        val state = restoredInstanceState
        if (state == null) {
            mx.setScale(unitScale, unitScale)
            imageMatrix = mx
            currentZoom = 1f
            return true
        }
        restoredInstanceState = null
        val restoredScale = state.getFloat(STATE_SCALE)
        m[MSCALE_X] = restoredScale
        m[MSCALE_Y] = restoredScale
        currentZoom = restoredScale / unitScale
        m[MTRANS_X] = 0.5f * viewWidth - state.getFloat(STATE_IMG_FOCUS_X)
        m[MTRANS_Y] = 0.5f * viewHeight - state.getFloat(STATE_IMG_FOCUS_Y)
        mx.setValues(m)
        applyConstraintsAndPushMatrix()
        return true
    }

    private suspend fun animateZoom(
            fromScale: Float, toScale: Float,
            fromTransX: Float, toTransX: Float,
            fromTransY: Float, toTransY: Float
    ) {
        ValueAnimator.ofPropertyValuesHolder(
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
                m[MSCALE_X] = scale
                m[MSCALE_Y] = scale
                m[MTRANS_X] = transX
                m[MTRANS_Y] = transY
                mx.setValues(m)
                imageMatrix = mx
                currentZoom = scale / unitScale
            }
            run()
        }
    }

    private fun startFling(velocityX: Float, velocityY: Float) {
        val oldFling = flingJob
        val context = context
        oldFling?.cancel()
        flingJob = start {
            withState(FLING) {
                loadMatrix()
                val startX = m[MTRANS_X].toInt()
                val startY = m[MTRANS_Y].toInt()
                val (imageWidth, imageHeight) = imageSize(pointF)
                val (minTransX, maxTransX) = transBounds(viewWidth, imageWidth, pointF)
                val (minTransY, maxTransY) = transBounds(viewHeight, imageHeight, pointF)
                val scroller = OverScroller(context).apply {
                    fling(startX, startY,
                            velocityX.roundToInt(), velocityY.roundToInt(),
                            minTransX.roundToInt(), maxTransX.roundToInt(),
                            minTransY.roundToInt(), maxTransY.roundToInt(),
                            overdrag, overdrag)
                }
                var currX = startX
                var currY = startY
                while (scroller.computeScrollOffset()) {
                    val newX = scroller.currX
                    val newY = scroller.currY
                    val deltaX = newX - currX
                    val deltaY = newY - currY
                    currX = newX
                    currY = newY
                    mx.postTranslate(deltaX.toFloat(), deltaY.toFloat())
                    imageMatrix = mx
                    UI.awaitFrame()
                }
            }
        }
    }

    /**
     * Returns the bounds for the translation of the image: (minAllowed, maxAllowed)
     */
    private fun transBounds(viewSize: Int, imgSize: Float, outParam: PointF): PointF {
        val bp = BLACK_BORDER_PROPORTION
        val borderCoord1 = viewSize * bp
        val borderCoord2 = viewSize * (1 - bp)
        val imgWithinBorders = imgSize <= viewSize * (1 - 2 * bp)
        if (imgWithinBorders) {
            outParam.set(borderCoord1, borderCoord2 - imgSize)
        }
        else {
            outParam.set(borderCoord2 - imgSize, borderCoord1)
        }
        return outParam
    }

    private fun applyConstraintsAndPushMatrix() {
        mx.getValues(m)
        val (imageWidth, imageHeight) = imageSize(pointF)
        m[MTRANS_X] = coerceToRange(m[MTRANS_X], addOverdrag(transBounds(viewWidth, imageWidth, pointF)))
        m[MTRANS_Y] = coerceToRange(m[MTRANS_Y], addOverdrag(transBounds(viewHeight, imageHeight, pointF)))
        mx.setValues(m)
        imageMatrix = mx
    }

    /**
     * "x" and "y" are lower and upper bounds for allowed image translation
     */
    private fun addOverdrag(transBounds: PointF) : PointF {
        transBounds.x -= overdrag
        transBounds.y += overdrag
        return transBounds
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

    private fun loadMatrix() {
        mx.set(imageMatrix)
        mx.getValues(m)
    }

    private inner class GestureListener : SimpleOnGestureListener() {

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            return doubleTapListener?.onSingleTapConfirmed(e) ?: performClick()
        }

        override fun onLongPress(e: MotionEvent) {
            performLongClick()
        }

        override fun onFling(e1: MotionEvent, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            startFling(velocityX, velocityY)
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
                        applyConstraintsAndPushMatrix()
                    }
                    ACTION_UP, ACTION_POINTER_UP -> if (state == DRAG) {
                        val (imageWidth, imageHeight) = imageSize(pointF)
                        val (minTransX, maxTransX) = transBounds(viewWidth, imageWidth, pointF)
                        val (minTransY, maxTransY) = transBounds(viewHeight, imageHeight, pointF)
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
            val scale = detector.currentSpan / initialSpan
            currentZoom = initialZoom * scale
            mx.set(initialMatrix)
            mx.postTranslate(detector.focusX - initFocusX, detector.focusY - initFocusY)
            mx.postScale(scale, scale, detector.focusX, detector.focusY)
            applyConstraintsAndPushMatrix()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            super.onScaleEnd(detector)
            state = NONE
            val targetZoom = coerceToRange(currentZoom, pointF.apply { set(MIN_ZOOM, MAX_ZOOM) })
            if (targetZoom != currentZoom) {
                start { animateZoomTo(targetZoom) }
            }
        }

        private suspend fun animateZoomTo(targetZoom: Float) {
            val startZoom = currentZoom
            val (initFocusX, initFocusY) = initialFocus
            val initialMatrix = Matrix(imageMatrix)
            withState(ANIMATE_ZOOM) {
                ValueAnimator.ofFloat(startZoom, targetZoom).apply {
                    duration = ZOOM_DURATION
                    interpolator = quadratic
                    addUpdateListener { anim ->
                        currentZoom = anim.animatedValue as Float
                        mx.set(initialMatrix)
                        mx.postScale(
                                currentZoom / startZoom, currentZoom / startZoom,
                                initFocusX, initFocusY)
                        applyConstraintsAndPushMatrix()
                    }
                    run()
                }
            }
        }
    }
}

private fun coerceToRange(x: Float, range: PointF): Float {
    val (rangeStart, rangeEnd) = range
    return x.coerceIn(rangeStart, rangeEnd)
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
