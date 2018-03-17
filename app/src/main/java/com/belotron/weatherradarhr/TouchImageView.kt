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

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Matrix.MSCALE_X
import android.graphics.Matrix.MSCALE_Y
import android.graphics.Matrix.MTRANS_X
import android.graphics.Matrix.MTRANS_Y
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
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.ImageView.ScaleType.CENTER
import android.widget.ImageView.ScaleType.CENTER_CROP
import android.widget.ImageView.ScaleType.CENTER_INSIDE
import android.widget.ImageView.ScaleType.FIT_CENTER
import android.widget.ImageView.ScaleType.FIT_END
import android.widget.ImageView.ScaleType.FIT_START
import android.widget.ImageView.ScaleType.FIT_XY
import android.widget.ImageView.ScaleType.MATRIX
import android.widget.OverScroller
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.cancelAndJoin
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.suspendCoroutine
import kotlin.math.max
import kotlin.math.min


private val ZOOM_TIME = TimeUnit.MILLISECONDS.toNanos(500).toFloat()
private val ACCEL_DECEL_CURVE = AccelerateDecelerateInterpolator()

// SuperMin and SuperMax multipliers. Determine how much the image can be
// zoomed below or above the zoom boundaries, before animating back to the
// min/max zoom boundary.
private const val SUPER_MIN_MULTIPLIER = .75f
private const val SUPER_MAX_MULTIPLIER = 1.25f
private const val OVERFLING = 70

private enum class State {
    NONE, DRAG, ZOOM, FLING, ANIMATE_ZOOM
}

class TouchImageView : ImageView {

    /**
     * The current zoom. This is the zoom relative to the initial
     * scale, not the original resource.
     */
    var currentZoom: Float = 1f; private set

    // Matrix applied to image. MSCALE_X and MSCALE_Y should always be equal.
    // MTRANS_X and MTRANS_Y are the other values used. prevMatrix is the matrix
    // saved prior to the screen rotating.
    private val currMatrix = Matrix()
    private val prevMatrix = Matrix()
    private var m = FloatArray(9)

    private var state = State.NONE

    private var defaultScale = 0f
    private var minScale = 1f
    private var maxScale = 128f
    private var superMinScale = 0f
    private var superMaxScale = 0f

    private lateinit var scaleType: ImageView.ScaleType

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
        superMinScale = SUPER_MIN_MULTIPLIER * minScale
        superMaxScale = SUPER_MAX_MULTIPLIER * maxScale
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
        val (bitmapW, bitmapH) = bitmapSize
        if (bitmapW == 0 || bitmapH == 0) {
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

    suspend fun animateZoomEnter(bitmapX: Float, bitmapY: Float) {
        val (_, bitmapH) = bitmapSizeF
        val zoomTo = (viewHeight.toFloat() / bitmapH) / defaultScale
        animateZoom(zoomTo, bitmapX, bitmapY, false)
    }

    suspend fun animateZoomExit() {
        val zoomTo = (viewWidth.toFloat() / drawable!!.intrinsicWidth) / defaultScale
        if (Math.abs(currentZoom - zoomTo) < 0.05) return
        animateZoom(zoomTo, 0f, 0f, false)
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
     * Resets the zoom and the translation to the initial state.
     */
    private fun resetZoom() {
        currentZoom = 1f
        fitImageToView()
    }

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
        val (bitmapW, bitmapH) = bitmapSize
        if (bitmapW == 0 || bitmapH == 0) {
            return
        }

        val scale = findDefaultScale()
        defaultScale = scale

        // Center the image
        val redundantXSpace = viewWidth - scale * bitmapW
        val redundantYSpace = viewHeight - scale * bitmapH
        matchViewWidth = viewWidth - redundantXSpace
        matchViewHeight = viewHeight - redundantYSpace

        if (!isZoomed && !imageRenderedAtLeastOnce) {
            // The view has been freshly created. Stretch and center image to fit.
            currMatrix.setScale(scale, scale)
            currMatrix.postTranslate(redundantXSpace / 2, redundantYSpace / 2)
            // Set the initial zoom so that the image covers the width of the view.
            // This matches the scale in radar image overview.
            val initialZoom = (viewWidth.toDouble() / bitmapW) / scale
            scaleImage(initialZoom, (viewWidth / 2).toFloat(), (viewHeight / 2).toFloat(), false)
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

    private fun findDefaultScale(): Float {
        val drawable = drawable!!
        val scaleX = viewWidth.toFloat() / drawable.intrinsicWidth
        val scaleY = viewHeight.toFloat() / drawable.intrinsicHeight
        return when (scaleType) {
            CENTER -> 1f
            CENTER_CROP -> Math.max(scaleX, scaleY)
            CENTER_INSIDE -> Math.min(1f, Math.min(scaleX, scaleY))
            FIT_CENTER -> Math.min(scaleX, scaleY)
            FIT_XY -> scaleX
            else -> throw UnsupportedOperationException("TouchImageView does not support FIT_START or FIT_END")
        }
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
    private fun transformCoordsBitmapToView(bx: Float, by: Float): PointF {
        currMatrix.getValues(m)
        val origW = drawable.intrinsicWidth.toFloat()
        val origH = drawable.intrinsicHeight.toFloat()
        val px = bx / origW
        val py = by / origH
        val finalX = m[MTRANS_X] + imageWidth * px
        val finalY = m[MTRANS_Y] + imageHeight * py
        return PointF(finalX, finalY)
    }

    private fun scaleImage(deltaScale: Double, focusX: Float, focusY: Float, stretchImageToSuper: Boolean) {
        val (lowerScale, upperScale) = if (stretchImageToSuper)
            Pair(superMinScale, superMaxScale) else
            Pair(minScale, maxScale)
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

    private suspend fun animateZoom(targetZoom: Float, bitmapX: Float, bitmapY: Float, stretchImageToSuper: Boolean) {
        val startTime = System.nanoTime()
        val startZoom = currentZoom
        val zoomChange = targetZoom - currentZoom
        val startPoint = transformCoordsBitmapToView(bitmapX, bitmapY)
        val endPoint = PointF((viewWidth / 2).toFloat(), (viewHeight / 2).toFloat())

        /**
         * Glide the image from initial position to where it should end up
         * so the double-tap position comes to the center of the view.
         */
        fun glideTowardsCenteredTouchPosition(progress: Float) {
            val targetX = startPoint.x + progress * (endPoint.x - startPoint.x)
            val targetY = startPoint.y + progress * (endPoint.y - startPoint.y)
            val curr = transformCoordsBitmapToView(bitmapX, bitmapY)
            currMatrix.postTranslate(targetX - curr.x, targetY - curr.y)
        }

        state = State.ANIMATE_ZOOM
        try {
            awaitAnimationStep()
            do {
                val timeProgress = Math.min(1f, (System.nanoTime() - startTime) / ZOOM_TIME)
                val zoomProgress = ACCEL_DECEL_CURVE.getInterpolation(timeProgress)
                val zoom = (startZoom + zoomProgress * zoomChange).toDouble()
                val deltaScale = zoom / currentZoom
                scaleImage(deltaScale, bitmapX, bitmapY, stretchImageToSuper)
                glideTowardsCenteredTouchPosition(zoomProgress)
                imageMatrix = currMatrix
                awaitAnimationStep()
            } while (zoomProgress < 1f)
        } finally {
            state = State.NONE
        }
    }

    private fun startFling(velocityX: Float, velocityY: Float) {
        val oldFling = flingJob
        val context = context
        flingJob = start {
            oldFling?.cancelAndJoin()
            state = State.FLING
            try {
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
            } finally {
                state = State.NONE
            }
        }
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

            if (state == State.NONE || state == State.DRAG || state == State.FLING) {
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
            scaleImage(detector.scaleFactor.toDouble(), touchPoint.x, touchPoint.y, true)
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
            if (currentZoom > maxScale) {
                targetZoom = maxScale
                animateToZoomBoundary = true
            } else if (currentZoom < minScale) {
                targetZoom = minScale
                animateToZoomBoundary = true
            }
            if (animateToZoomBoundary) {
                val (bitmapW, bitmapH) = bitmapSizeF ?: return
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
