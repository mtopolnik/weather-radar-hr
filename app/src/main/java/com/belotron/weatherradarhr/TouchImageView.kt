package com.belotron.weatherradarhr

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.PropertyValuesHolder
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Matrix.MSCALE_X
import android.graphics.Matrix.MSCALE_Y
import android.graphics.Matrix.MTRANS_X
import android.graphics.Matrix.MTRANS_Y
import android.graphics.Point
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.net.Uri
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

private val quadratic = TimeInterpolator { t ->
    if (t < 0.5) 2 * t * t
    else (1 - 2 * (1 - t) * (1 - t))
}

private enum class State {
    NONE, DRAG, ZOOM, FLING, ANIMATE_ZOOM
}

class TouchImageView : ImageView {

    private val overdrag = resources.getDimensionPixelOffset(R.dimen.overdrag)

    // Matrix applied to the image. MSCALE_X and MSCALE_Y should always be equal.
    // MTRANS_X and MTRANS_Y can be changed as needed. prevMatrix is the matrix
    // saved prior to the screen rotating.
    private val currMatrix = Matrix()
    private val prevMatrix = Matrix()

    // Reusable value containers
    private var m = FloatArray(9)
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

    // initial coordinates of the image inside the view,
    // when entering the full screen view
    private var fromImgX: Int = 0
    private var fromImgY: Int = 0

    // Size of view. Before and After rotation.
    private var viewWidth = 0
    private var viewHeight = 0
    private var prevViewWidth = 0
    private var prevViewHeight = 0

    // Size of image when stretched to fit view. Before and After rotation.
    private var matchViewWidth = 0f
    private var matchViewHeight = 0f
    private var prevMatchViewWidth = 0f
    private var prevMatchViewHeight = 0f

    private var onDrawCalled = false
    private var imageRenderedAtLeastOnce = false
    private var onDrawContinuation: CancellableContinuation<Unit>? = null

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector
    private var flingJob: Job? = null
    private var userTouchListener: View.OnTouchListener? = null
    private var doubleTapListener: GestureDetector.OnDoubleTapListener? = null

    private val imageWidth: Float get() = matchViewWidth * currentZoom
    private val imageHeight: Float get() = matchViewHeight * currentZoom

    constructor(context: Context) : this(context, null, 0)

    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle) {
        isClickable = true
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, GestureListener())
        superMinScale = SUPER_MIN_MULTIPLIER * MIN_ZOOM
        superMaxScale = SUPER_MAX_MULTIPLIER * MAX_ZOOM
        imageMatrix = currMatrix
        setScaleType(MATRIX)
        super.setOnTouchListener(PrivateOnTouchListener())
    }

    override fun setImageResource(resId: Int) {
        super.setImageResource(resId)
        savePreviousImageValues()
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        savePreviousImageValues()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        savePreviousImageValues()
    }

    override fun setImageURI(uri: Uri) {
        super.setImageURI(uri)
        savePreviousImageValues()
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
        if (!onDrawCalled) {
            fitImageToView()
            onDrawCalled = true
        }
        imageRenderedAtLeastOnce = true
        onDrawContinuation?.apply {
            onDrawContinuation = null
            UI.resumeUndispatched(Unit)
        }
        super.onDraw(canvas)
    }

    override fun onSaveInstanceState(): Parcelable {
        currMatrix.getValues(m)
        return Bundle().apply {
            putParcelable("instanceState", super.onSaveInstanceState())
            putFloat("saveScale", currentZoom)
            putFloat("matchViewHeight", matchViewHeight)
            putFloat("matchViewWidth", matchViewWidth)
            putInt("viewWidth", viewWidth)
            putInt("viewHeight", viewHeight)
            putFloatArray("matrix", m)
            putBoolean("imageRendered", imageRenderedAtLeastOnce)
        }
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        if (state !is Bundle) {
            super.onRestoreInstanceState(state)
            return
        }
        with (state) {
            currentZoom = getFloat("saveScale")
            m = getFloatArray("matrix")
            prevMatrix.setValues(m)
            prevMatchViewHeight = getFloat("matchViewHeight")
            prevMatchViewWidth = getFloat("matchViewWidth")
            prevViewHeight = getInt("viewHeight")
            prevViewWidth = getInt("viewWidth")
            imageRenderedAtLeastOnce = getBoolean("imageRendered")
            super.onRestoreInstanceState(getParcelable("instanceState"))
        }
    }

    private fun savePreviousImageValues() {
        if (viewHeight == 0 || viewWidth == 0) return
        currMatrix.getValues(m)
        prevMatrix.setValues(m)
        prevMatchViewHeight = matchViewHeight
        prevMatchViewWidth = matchViewWidth
        prevViewHeight = viewHeight
        prevViewWidth = viewWidth
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        savePreviousImageValues()
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
        currMatrix.getValues(m)
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
        currMatrix.getValues(m)
        val initialScale = m[MSCALE_X]
        val targetScale = (viewWidth.toFloat() / drawable!!.intrinsicWidth)
        val initialTransX = m[MTRANS_X]
        val initialTransY = m[MTRANS_Y]
        val targetTransX = fromImgX.toFloat()
        val targetTransY = fromImgY.toFloat()
        animateZoom(initialScale, targetScale, initialTransX, targetTransX, initialTransY, targetTransY)
    }

    suspend fun awaitOnDraw() {
        if (onDrawCalled) return
        require(onDrawContinuation == null) { "Dangling drawReadyContinuation" }
        suspendCancellableCoroutine<Unit> { onDrawContinuation = it }
    }

    fun setOnDoubleTapListener(l: GestureDetector.OnDoubleTapListener) {
        doubleTapListener = l
    }

    fun reset() {
        onDrawCalled = false
        imageRenderedAtLeastOnce = false
    }

    private fun fitImageToView() {
        val (bitmapW, bitmapH) = bitmapSize(point) ?: return
        val scale = min(viewWidth.toFloat() / bitmapW, viewHeight.toFloat() / bitmapH)
        unitScale = scale
        matchViewWidth = bitmapW * scale
        matchViewHeight = bitmapH * scale

        if (!imageRenderedAtLeastOnce) {
            // The view has been freshly created. Stretch and center image to fit.
            currentZoom = 1f
            currMatrix.setScale(scale, scale)
            imageMatrix = currMatrix
            return
        }

        // Reaching this point means the view was reconstructed after rotation.
        // Place the image on the screen according to the dimensions of the
        // previous image matrix.

        // These values should never be 0 or we will set viewWidth and viewHeight
        // to NaN in translateMatrixAfterRotate. To avoid this, call
        // savePreviousImageValues to set them equal to the current values.
        if (prevMatchViewWidth == 0f || prevMatchViewHeight == 0f) {
            savePreviousImageValues()
        }
        prevMatrix.getValues(m)

        // Rescale Matrix after rotation
        m[MSCALE_X] = matchViewWidth / bitmapW * currentZoom
        m[MSCALE_Y] = matchViewHeight / bitmapH * currentZoom

        fun translateMatrixAfterRotate(
                axis: Int, imgSizeBefore: Float, imgSizeNow: Float,
                viewSizeBefore: Int, viewSizeNow: Int, bitmapSize: Int
        ) {
            when {
                imgSizeNow <= viewSizeNow -> {
                    // Image is smaller than the view. C enter it.
                    m[axis] = 0.5f * (viewSizeNow - bitmapSize * m[MSCALE_X])
                }
                m[axis] > 0 -> {
                    // Image is larger than the view, but wasn't before rotation. Center it.
                    m[axis] = -0.5f * (imgSizeNow - viewSizeNow)
                }
                else -> {
                    // Find the area of the image which was previously centered in the view. Determine its distance
                    // from the left/top side of the view as a fraction of the entire image's width/height. Use that
                    // percentage to calculate the trans in the new view width/height.
                    val proportion = (Math.abs(m[axis]) + 0.5f * viewSizeBefore) / imgSizeBefore
                    m[axis] = -(proportion * imgSizeNow - viewSizeNow * 0.5f)
                }
            }
        }

        // Width
        val prevActualWidth = prevMatchViewWidth * currentZoom
        translateMatrixAfterRotate(MTRANS_X, prevActualWidth, imageWidth, prevViewWidth, viewWidth, bitmapW)

        // Height
        val prevActualHeight = prevMatchViewHeight * currentZoom
        translateMatrixAfterRotate(MTRANS_Y, prevActualHeight, imageHeight, prevViewHeight, viewHeight, bitmapH)

        // Set the matrix to the adjusted scale and translate values.
        currMatrix.setValues(m)
        imageMatrix = currMatrix
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
                currMatrix.getValues(m)
                val scale = anim.getAnimatedValue("scale") as Float
                val transX = anim.getAnimatedValue("transX") as Float
                val transY = anim.getAnimatedValue("transY") as Float
                m[MSCALE_X] = scale
                m[MSCALE_Y] = scale
                m[MTRANS_X] = transX
                m[MTRANS_Y] = transY
                currMatrix.setValues(m)
                currentZoom = scale / unitScale
                imageMatrix = currMatrix
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
                currMatrix.getValues(m)
                val startX = m[MTRANS_X].toInt()
                val startY = m[MTRANS_Y].toInt()
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
                    currMatrix.postTranslate(deltaX.toFloat(), deltaY.toFloat())
                    imageMatrix = currMatrix
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

    private fun constrainTranslate() {
        currMatrix.getValues(m)
        m[MTRANS_X] = coerceToRange(m[MTRANS_X], addOverdrag(transBounds(viewWidth, imageWidth, pointF)))
        m[MTRANS_Y] = coerceToRange(m[MTRANS_Y], addOverdrag(transBounds(viewHeight, imageHeight, pointF)))
        currMatrix.setValues(m)
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
                        initialMatrix.set(currMatrix)
                        flingJob?.cancel()
                        state = DRAG
                    }
                    ACTION_MOVE -> if (state == DRAG) {
                        val deltaX = event.x - initial.x
                        val deltaY = event.y - initial.y
                        currMatrix.set(initialMatrix)
                        currMatrix.postTranslate(deltaX, deltaY)
                        constrainTranslate()
                    }
                    ACTION_UP, ACTION_POINTER_UP -> if (state == DRAG) {
                        val (minTransX, maxTransX) = transBounds(viewWidth, imageWidth, pointF)
                        val (minTransY, maxTransY) = transBounds(viewHeight, imageHeight, pointF)
                        currMatrix.getValues(m)
                        if (m[MTRANS_X] !in minTransX..maxTransX || m[MTRANS_Y] !in minTransY..maxTransY) {
                            startFling(0f, 0f)
                        } else {
                            state = NONE
                        }
                    }
                }
            }
            imageMatrix = currMatrix
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
            initialMatrix.set(currMatrix)
            initialFocus.set(detector.focusX, detector.focusY)
            initialSpan = detector.currentSpan
            initialZoom = currentZoom
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val (initFocusX, initFocusY) = initialFocus
            val scale = detector.currentSpan / initialSpan
            currentZoom = initialZoom * scale
            currMatrix.set(initialMatrix)
            currMatrix.postTranslate(detector.focusX - initFocusX, detector.focusY - initFocusY)
            currMatrix.postScale(scale, scale, detector.focusX, detector.focusY)
            constrainTranslate()
            imageMatrix = currMatrix
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
            val initialMatrix = Matrix(currMatrix)
            withState(ANIMATE_ZOOM) {
                ValueAnimator.ofFloat(startZoom, targetZoom).apply {
                    duration = ZOOM_DURATION
                    interpolator = quadratic
                    addUpdateListener { anim ->
                        currentZoom = anim.animatedValue as Float
                        currMatrix.set(initialMatrix)
                        currMatrix.postScale(
                                currentZoom / startZoom, currentZoom / startZoom,
                                initFocusX, initFocusY)
                        constrainTranslate()
                        imageMatrix = currMatrix
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
