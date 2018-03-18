/*
 * TouchImageView.java
 * By: Michael Ortiz
 * Updated By: Patrick Lackemacher
 * Updated By: Babay88
 * Updated By: @ipsilondev
 * Updated By: hank-cp
 * Updated By: singpolyma
 * Converted to Kotlin and updated by: mtopolnik
 * -------------------
 * Extends Android ImageView to include pinch zooming, panning, fling and double tap zoom.
 */

package com.belotron.weatherradarhr

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.PropertyValuesHolder
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
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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
import com.belotron.weatherradarhr.State.FLING
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.cancelAndJoin
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.suspendCoroutine
import kotlin.math.max
import kotlin.math.min

private const val SUPER_MIN_MULTIPLIER = .75f
private const val SUPER_MAX_MULTIPLIER = 1.25f
private const val OVERFLING = 70
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 128f

private enum class State {
    NONE, DRAG, ZOOM, FLING, ANIMATE_ZOOM
}

class TouchImageView : ImageView {

    /**
     * The current zoom. This is the zoom relative to the initial
     * scale, not the original resource.
     */
    var currentZoom: Float = 1f; private set

    // Matrix applied to the image. MSCALE_X and MSCALE_Y should always be equal.
    // MTRANS_X and MTRANS_Y can be changed as needed. prevMatrix is the matrix
    // saved prior to the screen rotating.
    private val currMatrix = Matrix()
    private val prevMatrix = Matrix()

    // Reusable value containers
    private var m = FloatArray(9)
    private val pointF = PointF()
    private val point = Point()

    private var state = State.NONE

    private var defaultScale = 0f
    private var superMinScale = 0f
    private var superMaxScale = 0f

    private lateinit var scaleType: ImageView.ScaleType

    // initial coordinates of the image on the screen, when entering
    // the full screen view
    private var initialImgX: Int = 0
    private var initialImgY: Int = 0

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
    private var onDrawContinuation: Continuation<Unit>? = null

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector
    private var flingJob: Job? = null
    private var userTouchListener: View.OnTouchListener? = null
    private var doubleTapListener: GestureDetector.OnDoubleTapListener? = null

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
        info { "onMeasure" }
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
        fitImageToView()
    }

    override fun onDraw(canvas: Canvas) {
        onDrawCalled = true
        imageRenderedAtLeastOnce = true
        onDrawContinuation?.apply {
            onDrawContinuation = null
            resume(Unit)
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        savePreviousImageValues()
    }

    override fun setOnTouchListener(l: View.OnTouchListener) {
        userTouchListener = l
    }

    /**
     * startImgX,Y say where on the screen should the image be at the start of
     * zoom animation
     */
    suspend fun animateZoomEnter(startImgX: Int, startImgY: Int, bitmapX: Float, bitmapY: Float) {
        val (_, bitmapH) = bitmapSize(pointF) ?: return
        currMatrix.getValues(m)
        initialImgX = startImgX
        initialImgY = startImgY
        m[MTRANS_X] = startImgX.toFloat()
        m[MTRANS_Y] = startImgY.toFloat()
        currMatrix.setValues(m)
        imageMatrix = currMatrix
        val zoomTo = (viewHeight.toFloat() / bitmapH) / defaultScale
        animateZoom(zoomTo, bitmapX, bitmapY, false)
    }

    suspend fun animateZoomExit() {
        currMatrix.getValues(m)
        val initialScale = m[MSCALE_X]
        val targetScale = (viewWidth.toFloat() / drawable!!.intrinsicWidth)
        val initialTransX = m[MTRANS_X]
        val initialTransY = m[MTRANS_Y]
        val targetTransX = initialImgX.toFloat()
        val targetTransY = initialImgY.toFloat()
        withState(ANIMATE_ZOOM) {
            ValueAnimator.ofPropertyValuesHolder(
                    PropertyValuesHolder.ofFloat("scale", initialScale, targetScale),
                    PropertyValuesHolder.ofFloat("transX", initialTransX, targetTransX),
                    PropertyValuesHolder.ofFloat("transY", initialTransY, targetTransY)
            ).apply {
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
                    imageMatrix = currMatrix
                }
                startAndJoin()
            }
        }
    }

    suspend fun awaitOnDraw() {
        if (onDrawCalled) return
        require(onDrawContinuation == null) { "Dangling drawReadyContinuation" }
        suspendCoroutine<Unit> { onDrawContinuation = it }
    }

    fun setOnDoubleTapListener(l: GestureDetector.OnDoubleTapListener) {
        doubleTapListener = l
    }

    fun resetToNeverDrawn() {
        onDrawCalled = false
        imageRenderedAtLeastOnce = false
    }

    /**
     * False if image is in initial, unzoomed state. True otherwise.
     */
    private val isZoomed get() = currentZoom != 1f

    private val imageWidth: Float get() = matchViewWidth * currentZoom
    private val imageHeight: Float get() = matchViewHeight * currentZoom

    /**
     * Saves the current matrix and view dimensions
     * in the prevMatrix and prevView variables.
     */
    private fun savePreviousImageValues() {
        if (viewHeight == 0 || viewWidth == 0) return
        currMatrix.getValues(m)
        prevMatrix.setValues(m)
        prevMatchViewHeight = matchViewHeight
        prevMatchViewWidth = matchViewWidth
        prevViewHeight = viewHeight
        prevViewWidth = viewWidth
    }

    private fun fitImageToView() {
        val (bitmapW, bitmapH) = bitmapSize(point) ?: return
        val scale = viewWidth.toFloat() / bitmapW
        defaultScale = scale

        // Center the image
        val redundantYSpace = viewHeight - scale * bitmapH
        matchViewWidth = viewWidth.toFloat()
        matchViewHeight = viewHeight - redundantYSpace

        if (!isZoomed && !imageRenderedAtLeastOnce) {
            // The view has been freshly created. Stretch and center image to fit.
            currMatrix.setScale(scale, scale)
            adjustScale(1.0, (viewWidth / 2).toFloat(), (viewHeight / 2).toFloat(), false)
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

        // TransX and TransY from previous matrix
        val transX = m[MTRANS_X]
        val transY = m[MTRANS_Y]

        // Width
        val prevActualWidth = prevMatchViewWidth * currentZoom
        val actualWidth = imageWidth
        translateMatrixAfterRotate(MTRANS_X, transX, prevActualWidth, actualWidth,
                prevViewWidth, viewWidth, bitmapW)

        // Height
        val prevActualHeight = prevMatchViewHeight * currentZoom
        val actualHeight = imageHeight
        translateMatrixAfterRotate(MTRANS_Y, transY, prevActualHeight, actualHeight,
                prevViewHeight, viewHeight, bitmapH)

        // Set the matrix to the adjusted scale and translate values.
        currMatrix.setValues(m)
        imageMatrix = currMatrix
    }

    /**
     * After rotating the matrix needs to be translated. This function finds
     * the area of the image which was previously centered and adjusts
     * translations so that is again the center, post-rotation.
     *
     * @param axis          Matrix.MTRANS_X or Matrix.MTRANS_Y
     * @param trans         the value of trans in that axis before the rotation
     * @param prevImageSize the width/height of the image before the rotation
     * @param imageSize     width/height of the image after rotation
     * @param prevViewSize  width/height of view before rotation
     * @param viewSize      width/height of view after rotation
     * @param drawableSize  width/height of drawable
     */
    private fun translateMatrixAfterRotate(
            axis: Int, trans: Float, prevImageSize: Float, imageSize: Float,
            prevViewSize: Int, viewSize: Int, drawableSize: Int
    ) {
        when {
            imageSize < viewSize -> {
                // The width/height of image is less than the view's width/height. Center it.
                m[axis] = (viewSize - drawableSize * m[MSCALE_X]) * 0.5f
            }
            trans > 0 -> {
                // The image is larger than the view, but was not before rotation. Center it.
                m[axis] = -((imageSize - viewSize) * 0.5f)
            }
            else -> {
                // Find the area of the image which was previously centered in the view. Determine its distance
                // from the left/top side of the view as a fraction of the entire image's width/height. Use that
                // percentage to calculate the trans in the new view width/height.
                val percentage = (Math.abs(trans) + 0.5f * prevViewSize) / prevImageSize
                m[axis] = -(percentage * imageSize - viewSize * 0.5f)
            }
        }
    }

    /**
     * Inverse of transformCoordTouchToBitmap. This function will transform
     * the coordinates in the drawable's coordinate system to the view's
     * coordinate system.
     *
     * @param bx x-coordinate in original bitmap coordinate system
     * @param by y-coordinate in original bitmap coordinate system
     * @return Coordinates of the point in the view's coordinate system.
     */
    private fun transformCoordsBitmapToView(bx: Float, by: Float, pointF: PointF): PointF {
        currMatrix.getValues(m)
        val (origW, origH) = bitmapSize(pointF)!!
        val px = bx / origW
        val py = by / origH
        pointF.set(m[MTRANS_X] + imageWidth * px, m[MTRANS_Y] + imageHeight * py)
        return pointF
    }

    private fun adjustScale(deltaScale: Double, focusX: Float, focusY: Float, stretchImageToSuper: Boolean) {
        val (lowerScale, upperScale) = if (stretchImageToSuper)
            Pair(superMinScale, superMaxScale) else
            Pair(MIN_ZOOM, MAX_ZOOM)
        val origScale = currentZoom
        currentZoom *= deltaScale.toFloat()
        val deltaScale1 = when {
            currentZoom > upperScale -> {
                currentZoom = upperScale
                (upperScale / origScale).toDouble()
            }
            currentZoom < lowerScale -> {
                currentZoom = lowerScale
                (lowerScale / origScale).toDouble()
            }
            else -> deltaScale
        }
        currMatrix.postScale(deltaScale1.toFloat(), deltaScale1.toFloat(), focusX, focusY)
    }

    /**
     * focusX, focusY refer to the bitmap's intrinsic coordinate system
     */
    private suspend fun animateZoom(targetZoom: Float, focusX: Float, focusY: Float, stretchImageToSuper: Boolean) {
        val startZoom = currentZoom
        val zoomChange = targetZoom - currentZoom
        // starting position of the focus point in the view's coordinate system
        val (startFocusX, startFocusY) = transformCoordsBitmapToView(focusX, focusY, pointF)
        // target position of the focus point in the view's coordinate system
        val targetFocusX = viewWidth / 2f
        val targetFocusY = viewHeight / 2f

        /**
         * Glide the image from initial position to where it should end up
         * so the double-tap position comes to the center of the view.
         */
        fun glideTowardsCenteredTouchPosition(progress: Float) {
            val targetX = startFocusX + progress * (targetFocusX - startFocusX)
            val targetY = startFocusY + progress * (targetFocusY - startFocusY)
            val (currX, currY) = transformCoordsBitmapToView(focusX, focusY, pointF)
            currMatrix.postTranslate(targetX - currX, targetY - currY)
        }

        withState(ANIMATE_ZOOM) {
            ValueAnimator.ofFloat(startZoom, startZoom + zoomChange).apply {
                addUpdateListener { anim ->
                    val zoom = anim.animatedValue as Float
                    val deltaScale = zoom.toDouble() / currentZoom
                    adjustScale(deltaScale, startFocusX, startFocusY, stretchImageToSuper)
                    glideTowardsCenteredTouchPosition(anim.animatedFraction)
                    imageMatrix = currMatrix
                }
                startAndJoin()
            }
        }
    }

    private fun startFling(velocityX: Float, velocityY: Float) {
        val oldFling = flingJob
        val context = context
        flingJob = start {
            oldFling?.cancelAndJoin()
            withState(FLING) {
                currMatrix.getValues(m)
                val startX = m[MTRANS_X].toInt()
                val startY = m[MTRANS_Y].toInt()
                val minX = -imageWidth.toInt() + viewWidth / 2
                val maxX = viewWidth / 2
                val minY = -imageHeight.toInt() + viewHeight / 2
                val maxY = viewHeight / 2
                val scroller = OverScroller(context).apply {
                    fling(startX, startY,
                            velocityX.toInt(), velocityY.toInt(),
                            minX, maxX,
                            minY, maxY,
                            OVERFLING, OVERFLING)
                }
                var currX = startX
                var currY = startY
                while (scroller.computeScrollOffset()) {
                    val newX = scroller.currX
                    val newY = scroller.currY
                    val transX = newX - currX
                    val transY = newY - currY
                    currX = newX
                    currY = newY
                    currMatrix.postTranslate(transX.toFloat(), transY.toFloat())
                    imageMatrix = currMatrix
                    awaitAnimationStep()
                }
            }
        }
    }

    private suspend fun withState(state: State, block: suspend () -> Unit) = try {
        this.state = state
        block()
    } finally {
        this.state = State.NONE
    }

    /**
     * Gesture Listener detects a single click or long click and passes that on
     * to the view's listener.
     */
    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            return doubleTapListener?.onSingleTapConfirmed(e) ?: performClick()
        }

        override fun onLongPress(e: MotionEvent) {
            performLongClick()
        }

        override fun onFling(e1: MotionEvent, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            // If a previous fling is still active, it should be cancelled so that two flings
            // are not run simultaenously.
            startFling(velocityX, velocityY)
            return super.onFling(e1, e2, velocityX, velocityY)
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            return doubleTapListener != null && doubleTapListener!!.onDoubleTap(e)
        }

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            return doubleTapListener != null && doubleTapListener!!.onDoubleTapEvent(e)
        }
    }

    /**
     * Responsible for all touch events. Handles the heavy lifting of drag and
     * also sends touch events to Scale Detector and Gesture Detector.
     */
    private inner class PrivateOnTouchListener : View.OnTouchListener {

        // Remember last point position for dragging
        private val last = PointF()

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            val curr = PointF(event.x, event.y)

            if (state == State.NONE || state == State.DRAG || state == FLING) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        last.set(curr.x, curr.y)
                        flingJob?.cancel()
                        state = State.DRAG
                    }

                    MotionEvent.ACTION_MOVE -> if (state == State.DRAG) {
                        val deltaX = curr.x - last.x
                        val deltaY = curr.y - last.y
                        last.set(curr.x, curr.y)
                        currMatrix.postTranslate(deltaX, deltaY)
                        currMatrix.getValues(m)
                        m[MTRANS_X] = max(-imageWidth + viewWidth / 2, m[MTRANS_X])
                        m[MTRANS_Y] = max(-imageHeight + viewHeight / 2, m[MTRANS_Y])
                        m[MTRANS_X] = min(viewWidth / 2f, m[MTRANS_X])
                        m[MTRANS_Y] = min(viewHeight / 2f, m[MTRANS_Y])
                        currMatrix.setValues(m)
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                        state = State.NONE
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

    /**
     * Detects two-finger scaling and scales the image.
     *
     * @author Ortiz
     */
    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        var prevTouchPoint: PointF? = null

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            state = State.ZOOM
            prevTouchPoint = null
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val touchPoint = PointF(detector.focusX, detector.focusY)
            adjustScale(detector.scaleFactor.toDouble(), touchPoint.x, touchPoint.y, true)
            prevTouchPoint?.also {
                val deltaX = touchPoint.x - it.x
                val deltaY = touchPoint.y - it.y
                currMatrix.postTranslate(deltaX, deltaY)
            }
            prevTouchPoint = touchPoint
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            super.onScaleEnd(detector)
            state = State.NONE
            var animateToZoomBoundary = false
            var targetZoom = currentZoom
            if (currentZoom > MAX_ZOOM) {
                targetZoom = MAX_ZOOM
                animateToZoomBoundary = true
            } else if (currentZoom < MIN_ZOOM) {
                targetZoom = MIN_ZOOM
                animateToZoomBoundary = true
            }
            if (animateToZoomBoundary) {
                val (bitmapW, bitmapH) = bitmapSize(pointF) ?: return
                start { animateZoom(targetZoom, (bitmapW / 2), (bitmapH / 2), true) }
            }
        }
    }
}

private fun computeViewSize(mode: Int, requestedSize: Int, drawableSize: Int): Int {
    return when (mode) {
        AT_MOST -> Math.min(drawableSize, requestedSize)
        UNSPECIFIED -> drawableSize
        EXACTLY -> requestedSize
        else -> throw IllegalArgumentException("Undefined measure specification mode $mode")
    }
}

private suspend fun View.awaitAnimationStep() = suspendCoroutine<Unit> { postOnAnimation { it.resume(Unit) } }

private suspend fun ValueAnimator.startAndJoin() {
    suspendCoroutine<Unit> { cont ->
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                cont.resume(Unit)
            }
        })
        start()
    }
}
