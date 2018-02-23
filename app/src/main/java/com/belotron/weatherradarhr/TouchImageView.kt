/*
 * TouchImageView.java
 * By: Michael Ortiz
 * Updated By: Patrick Lackemacher
 * Updated By: Babay88
 * Updated By: @ipsilondev
 * Updated By: hank-cp
 * Updated By: singpolyma
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
import android.graphics.RectF
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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.suspendCoroutine

private val ZOOM_TIME = TimeUnit.MILLISECONDS.toNanos(500).toFloat()

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

    // Size of view and previous view size (ie before rotation)
    private var viewWidth = 0
    private var viewHeight = 0
    private var prevViewWidth = 0
    private var prevViewHeight = 0

    // Size of image when it is stretched to fit view. Before and After rotation.
    private var matchViewWidth = 0f
    private var matchViewHeight = 0f
    private var prevMatchViewWidth = 0f
    private var prevMatchViewHeight = 0f

    private var onDrawCalled = false
    private var imageRenderedAtLeastOnce = false
    private var onDrawContinuation: Continuation<Unit>? = null

    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector
    private var fling: Fling? = null
    private var userTouchListener: View.OnTouchListener? = null
    private var touchImageViewListener: (() -> Unit)? = null
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
            if (onDrawCalled) {
                // setScaleType() has been called programmatically, update TouchImageView
                // with the new scaleType.
                start { setZoom(this@TouchImageView) }
            }
        }
    }

    override fun getScaleType(): ImageView.ScaleType = scaleType

    override fun canScrollHorizontally(direction: Int): Boolean {
        currMatrix.getValues(m)
        val x = m[MTRANS_X]
        return when {
            imageWidth < viewWidth -> false
            x >= -1 && direction < 0 -> false
            Math.abs(x) + viewWidth.toFloat() + 1f >= imageWidth && direction > 0 -> false
            else -> true
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val drawable = drawable
        if (drawable == null || drawable.intrinsicWidth == 0 || drawable.intrinsicHeight == 0) {
            setMeasuredDimension(0, 0)
            return
        }
        val drawableWidth = drawable.intrinsicWidth
        val drawableHeight = drawable.intrinsicHeight
        val widthSize = View.MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = View.MeasureSpec.getMode(widthMeasureSpec)
        val heightSize = View.MeasureSpec.getSize(heightMeasureSpec)
        val heightMode = View.MeasureSpec.getMode(heightMeasureSpec)
        viewWidth = computeViewSize(widthMode, widthSize, drawableWidth)
        viewHeight = computeViewSize(heightMode, heightSize, drawableHeight)
        setMeasuredDimension(viewWidth, viewHeight)
        info {"onMeasure"}
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
        val state = Bundle()
        state.putParcelable("instanceState", super.onSaveInstanceState())
        state.putFloat("saveScale", currentZoom)
        state.putFloat("matchViewHeight", matchViewHeight)
        state.putFloat("matchViewWidth", matchViewWidth)
        state.putInt("viewWidth", viewWidth)
        state.putInt("viewHeight", viewHeight)
        currMatrix.getValues(m)
        state.putFloatArray("matrix", m)
        state.putBoolean("imageRendered", imageRenderedAtLeastOnce)
        return state
    }

    override fun onRestoreInstanceState(state: Parcelable) {
        if (state !is Bundle) {
            super.onRestoreInstanceState(state)
            return
        }
        currentZoom = state.getFloat("saveScale")
        m = state.getFloatArray("matrix")
        prevMatrix.setValues(m)
        prevMatchViewHeight = state.getFloat("matchViewHeight")
        prevMatchViewWidth = state.getFloat("matchViewWidth")
        prevViewHeight = state.getInt("viewHeight")
        prevViewWidth = state.getInt("viewWidth")
        imageRenderedAtLeastOnce = state.getBoolean("imageRendered")
        super.onRestoreInstanceState(state.getParcelable("instanceState"))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        savePreviousImageValues()
    }

    /**
     * A Rect representing the zoomed image.
     */
    val zoomedRect: RectF
        get() {
            if (scaleType == FIT_XY) {
                throw UnsupportedOperationException("getZoomedRect() not supported with FIT_XY")
            }
            val topLeft = transformCoordTouchToBitmap(0f, 0f, true)
            val bottomRight = transformCoordTouchToBitmap(viewWidth.toFloat(), viewHeight.toFloat(), true)

            val w = drawable.intrinsicWidth.toFloat()
            val h = drawable.intrinsicHeight.toFloat()
            return RectF(topLeft.x / w, topLeft.y / h, bottomRight.x / w, bottomRight.y / h)
        }

    /**
     * The max zoom multiplier. Default value: 3.
     */
    var maxZoom: Float
        get() = maxScale
        set(max) {
            maxScale = max
            superMaxScale = SUPER_MAX_MULTIPLIER * maxScale
        }

    /**
     * The min zoom multiplier. Default value: 1.
     */
    var minZoom: Float
        get() = minScale
        set(min) {
            minScale = min
            superMinScale = SUPER_MIN_MULTIPLIER * minScale
        }

    /**
     * The point at the center of the zoomed image. The PointF coordinates range
     * in value between 0 and 1 and the focus point is denoted as a fraction from the left
     * and top of the view. For example, the top left corner of the image would be (0, 0).
     * And the bottom right corner would be (1, 1).
     */
    val scrollPosition: PointF? get() {
        val drawable = drawable ?: return null
        val drawableWidth = drawable.intrinsicWidth
        val drawableHeight = drawable.intrinsicHeight

        val point = transformCoordTouchToBitmap((viewWidth / 2).toFloat(), (viewHeight / 2).toFloat(), true)
        point.x /= drawableWidth.toFloat()
        point.y /= drawableHeight.toFloat()
        return point
    }

    /**
     * False if image is in initial, unzoomed state. True otherwise.
     */
    private val isZoomed get() = currentZoom != 1f

    private val imageWidth: Float get() = matchViewWidth * currentZoom

    private val imageHeight: Float get() = matchViewHeight * currentZoom

    override fun setOnTouchListener(l: View.OnTouchListener) {
        userTouchListener = l
    }

    fun setOnTouchImageViewListener(l: () -> Unit) {
        touchImageViewListener = l
    }

    fun setOnDoubleTapListener(l: GestureDetector.OnDoubleTapListener) {
        doubleTapListener = l
    }

    fun resetToNeverDrawn() {
        onDrawCalled = false
        imageRenderedAtLeastOnce = false
    }

    suspend fun animateZoomEnter(e: MotionEvent) {
        val drawable = drawable!!
        val zoomTo = (viewHeight.toFloat() / drawable.intrinsicHeight) / defaultScale
        suspendCoroutine<Unit> {
            postOnAnimation(AnimateZoom(zoomTo, e.x, e.y, false, it))
        }
    }

    suspend fun animateZoomExit() {
        val zoomTo = (viewWidth.toFloat() / drawable!!.intrinsicWidth) / defaultScale
        if (Math.abs(currentZoom - zoomTo) < 0.05) return
        suspendCoroutine<Unit> {
            postOnAnimation(AnimateZoom(zoomTo, 0f, 0f, false, it))
        }
    }

    /**
     * Resets the zoom and the translation to the initial state.
     */
    fun resetZoom() {
        currentZoom = 1f
        fitImageToView()
    }

    /**
     * Set zoom to the specified scale. Image will be centered around the point
     * (focusX, focusY). These floats range from 0 to 1 and denote the focus point
     * as a fraction from the left and top of the view. For example, the top left
     * corner of the image would be (0, 0). And the bottom right corner would be (1, 1).
     *
     * @param scale
     * @param focusX
     * @param focusY
     * @param scaleType
     */
    suspend fun setZoom(scale: Float, focusX: Float = 0.5f, focusY: Float = 0.5f,
                scaleType: ImageView.ScaleType = this.scaleType
    ) {
        // setZoom can be called before the image is on the screen, but at this point,
        // image and view sizes have not yet been calculated in onMeasure. Delay calling
        // setZoom until the view has been measured.
        awaitOnDraw()
        if (scaleType != this.scaleType) {
            setScaleType(scaleType)
        }
        resetZoom()
        scaleImage(scale.toDouble(), (viewWidth / 2).toFloat(), (viewHeight / 2).toFloat(), true)
        currMatrix.getValues(m)
        m[MTRANS_X] = -(focusX * imageWidth - viewWidth * 0.5f)
        m[MTRANS_Y] = -(focusY * imageHeight - viewHeight * 0.5f)
        currMatrix.setValues(m)
        fixTrans()
        imageMatrix = currMatrix
    }

    /**
     * Set zoom parameters equal to another TouchImageView. Including scale, position,
     * and ScaleType.
     */
    suspend fun setZoom(img: TouchImageView) {
        val center = img.scrollPosition
        setZoom(img.currentZoom, center!!.x, center.y, img.getScaleType())
    }

    /**
     * Set the focus point of the zoomed image. The focus points are denoted as a fraction from the
     * left and top of the view. The focus points can range in value between 0 and 1.
     *
     * @param focusX
     * @param focusY
     */
    suspend fun setScrollPosition(focusX: Float, focusY: Float) {
        setZoom(currentZoom, focusX, focusY)
    }

    suspend fun awaitOnDraw() {
        if (onDrawCalled) return
        require(onDrawContinuation == null) { "Dangling drawReadyContinuation" }
        suspendCoroutine<Unit> { onDrawContinuation = it }
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

    /**
     * Performs boundary checking and fixes the image matrix if it
     * is out of bounds.
     */
    private fun fixTrans() {
        currMatrix.getValues(m)
        val transX = m[MTRANS_X]
        val transY = m[MTRANS_Y]

        val fixTransX = getFixTrans(transX, viewWidth.toFloat(), imageWidth)
        val fixTransY = getFixTrans(transY, viewHeight.toFloat(), imageHeight)

        if (fixTransX != 0f || fixTransY != 0f) {
            currMatrix.postTranslate(fixTransX, fixTransY)
        }
    }

    /**
     * When transitioning from zooming from focus to zoom from center (or vice versa)
     * the image can become unaligned within the view. This is apparent when zooming
     * quickly. When the content size is less than the view size, the content will often
     * be centered incorrectly within the view. fixScaleTrans first calls fixTrans() and
     * then makes sure the image is centered correctly within the view.
     */
    private fun fixScaleTrans() {
        fixTrans()
        currMatrix.getValues(m)
        if (imageWidth < viewWidth) {
            m[MTRANS_X] = (viewWidth - imageWidth) / 2
        }
        if (imageHeight < viewHeight) {
            m[MTRANS_Y] = (viewHeight - imageHeight) / 2
        }
        currMatrix.setValues(m)
    }

    private fun fitImageToView() {
        val drawable: Drawable? = drawable
        if (drawable == null || drawable.intrinsicWidth == 0 || drawable.intrinsicHeight == 0) {
            return
        }
        val drawableWidth = drawable.intrinsicWidth
        val drawableHeight = drawable.intrinsicHeight

        val scale = findDefaultScale()
        defaultScale = scale

        // Center the image
        val redundantXSpace = viewWidth - scale * drawableWidth
        val redundantYSpace = viewHeight - scale * drawableHeight
        matchViewWidth = viewWidth - redundantXSpace
        matchViewHeight = viewHeight - redundantYSpace

        if (!isZoomed && !imageRenderedAtLeastOnce) {
            // The view has been freshly created. Stretch and center image to fit.
            currMatrix.setScale(scale, scale)
            currMatrix.postTranslate(redundantXSpace / 2, redundantYSpace / 2)
            fixTrans()
            // Set the initial zoom so that the image covers the width of the view.
            // This matches the scale in radar image overview.
            val initialZoom = (viewWidth.toDouble() / drawableWidth) / scale
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
        m[MSCALE_X] = matchViewWidth / drawableWidth * currentZoom
        m[MSCALE_Y] = matchViewHeight / drawableHeight * currentZoom

        // TransX and TransY from previous matrix
        val transX = m[MTRANS_X]
        val transY = m[MTRANS_Y]

        // Width
        val prevActualWidth = prevMatchViewWidth * currentZoom
        val actualWidth = imageWidth
        translateMatrixAfterRotate(MTRANS_X, transX, prevActualWidth, actualWidth,
                prevViewWidth, viewWidth, drawableWidth)

        // Height
        val prevActualHeight = prevMatchViewHeight * currentZoom
        val actualHeight = imageHeight
        translateMatrixAfterRotate(MTRANS_Y, transY, prevActualHeight, actualHeight,
                prevViewHeight, viewHeight, drawableHeight)

        // Set the matrix to the adjusted scale and translate values.
        currMatrix.setValues(m)
        fixTrans()
        setImageMatrix(currMatrix)
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
     * This function will transform the coordinates in the touch event to the
     * coordinate system of the drawable that the imageview contain
     *
     * @param x            x-coordinate of touch event
     * @param y            y-coordinate of touch event
     * @param clipToBitmap Touch event may occur within view, but outside image content. True, to clip return value
     * to the bounds of the bitmap size.
     * @return Coordinates of the point touched, in the coordinate system of the original drawable.
     */
    private fun transformCoordTouchToBitmap(x: Float, y: Float, clipToBitmap: Boolean): PointF {
        currMatrix.getValues(m)
        val origW = drawable.intrinsicWidth.toFloat()
        val origH = drawable.intrinsicHeight.toFloat()
        val transX = m[MTRANS_X]
        val transY = m[MTRANS_Y]
        var finalX = (x - transX) * origW / imageWidth
        var finalY = (y - transY) * origH / imageHeight
        if (clipToBitmap) {
            finalX = Math.min(Math.max(finalX, 0f), origW)
            finalY = Math.min(Math.max(finalY, 0f), origH)
        }
        return PointF(finalX, finalY)
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
    private fun transformCoordBitmapToTouch(bx: Float, by: Float): PointF {
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
        fixScaleTrans()
    }

    private fun printMatrixInfo(msg: String) {
        val n = FloatArray(9)
        currMatrix.getValues(n)
        info { msg +
                " Scale: " + n[MSCALE_X] +
                " TransX: " + n[MTRANS_X] +
                " TransY: " + n[MTRANS_Y]
        }
    }

    /**
     * DoubleTapZoom calls a series of runnables which apply
     * an animated zoom in/out graphic to the image.
     */
    private inner class AnimateZoom
    internal constructor(
            targetZoom: Float, focusX: Float, focusY: Float,
            private val stretchImageToSuper: Boolean,
            private val continuation: Continuation<Unit>? = null
    ) : Runnable {
        private val startTime: Long
        private val startZoom: Float
        private val zoomChange: Float
        private val bitmapX: Float
        private val bitmapY: Float
        private val accelDecelCurve = AccelerateDecelerateInterpolator()
        private val startTouch: PointF
        private val endTouch: PointF

        init {
            state = State.ANIMATE_ZOOM
            startTime = System.nanoTime()
            this.startZoom = currentZoom
            this.zoomChange = targetZoom - currentZoom
            val bitmapPoint = transformCoordTouchToBitmap(focusX, viewHeight / 2f, false)
            this.bitmapX = bitmapPoint.x
            this.bitmapY = bitmapPoint.y

            // Used for translating image during scaling
            startTouch = transformCoordBitmapToTouch(bitmapX, bitmapY)
            endTouch = PointF((viewWidth / 2).toFloat(), (viewHeight / 2).toFloat())
        }

        override fun run() {
            try {
                val timeProgress = Math.min(1f, (System.nanoTime() - startTime) / ZOOM_TIME)
                val zoomProgress = accelDecelCurve.getInterpolation(timeProgress)
                val zoom = (startZoom + zoomProgress * zoomChange).toDouble()
                val deltaScale = zoom / currentZoom
                scaleImage(deltaScale, bitmapX, bitmapY, stretchImageToSuper)
                translateImageToCenterTouchPosition(zoomProgress)
                imageMatrix = currMatrix

                // OnTouchImageViewListener is set: double tap runnable updates listener
                // with every frame.
                if (touchImageViewListener != null) {
                    touchImageViewListener!!.invoke()
                }
                if (zoomProgress < 1f) {
                    postOnAnimation(this)
                } else {
                    state = State.NONE
                    continuation?.resume(Unit)
                }
            } catch (e: Throwable) {
                state = State.NONE
            }
        }

        /**
         * Interpolate between where the image should start and end in order to translate
         * the image so that the point that is touched is what ends up centered at the end
         * of the zoom.
         *
         * @param t
         */
        private fun translateImageToCenterTouchPosition(t: Float) {
            val targetX = startTouch.x + t * (endTouch.x - startTouch.x)
            val targetY = startTouch.y + t * (endTouch.y - startTouch.y)
            val curr = transformCoordBitmapToTouch(bitmapX, bitmapY)
            currMatrix.postTranslate(targetX - curr.x, targetY - curr.y)
            fixScaleTrans()
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
            fling?.cancelFling()
            fling = Fling(velocityX.toInt(), velocityY.toInt())
            postOnAnimation(fling)
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
                        last.set(curr)
                        fling?.cancelFling()
                        state = State.DRAG
                    }

                    MotionEvent.ACTION_MOVE -> if (state == State.DRAG) {
                        val deltaX = curr.x - last.x
                        val deltaY = curr.y - last.y
                        val fixTransX = getFixDragTrans(deltaX, viewWidth.toFloat(), imageWidth)
                        val fixTransY = getFixDragTrans(deltaY, viewHeight.toFloat(), imageHeight)
                        currMatrix.postTranslate(fixTransX, fixTransY)
                        fixTrans()
                        last.set(curr.x, curr.y)
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
            // OnTouchImageViewListener is set: TouchImageView dragged by user.
            if (touchImageViewListener != null) {
                touchImageViewListener!!.invoke()
            }
            return true
        }
    }

    /**
     * ScaleListener detects user two finger scaling and scales image.
     *
     * @author Ortiz
     */
    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            state = State.ZOOM
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleImage(detector.scaleFactor.toDouble(), detector.focusX, detector.focusY, true)
            touchImageViewListener?.invoke()
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
                val doubleTap = AnimateZoom(targetZoom, (viewWidth / 2).toFloat(), (viewHeight / 2).toFloat(), true)
                postOnAnimation(doubleTap)
            }
        }
    }

    /**
     * Fling launches sequential runnables which apply
     * the fling graphic to the image. The values for the translation
     * are interpolated by the Scroller.
     */
    private inner class Fling internal constructor(velocityX: Int, velocityY: Int) : Runnable {

        internal var scroller: OverScroller? = OverScroller(context)
        internal var currX: Int = 0
        internal var currY: Int = 0

        init {
            state = State.FLING
            currMatrix.getValues(m)
            val startX = m[MTRANS_X].toInt()
            val startY = m[MTRANS_Y].toInt()
            val (minX, maxX) = if (imageWidth > viewWidth)
                Pair(viewWidth - imageWidth.toInt(), 0) else
                Pair(startX, startX)
            val (minY, maxY) = if (imageHeight > viewHeight)
                Pair(viewHeight - imageHeight.toInt(), 0) else
                Pair(startY, startY)
            scroller!!.fling(startX, startY, velocityX, velocityY, minX, maxX, minY, maxY)
            currX = startX
            currY = startY
        }

        fun cancelFling() {
            val scroller = scroller ?: return
            state = State.NONE
            scroller.forceFinished(true)
        }

        override fun run() {
            touchImageViewListener?.invoke()
            val scroller = scroller!!
            if (scroller.isFinished) {
                this.scroller = null
                return
            }
            if (scroller.computeScrollOffset()) {
                val newX = scroller.currX
                val newY = scroller.currY
                val transX = newX - currX
                val transY = newY - currY
                currX = newX
                currY = newY
                currMatrix.postTranslate(transX.toFloat(), transY.toFloat())
                fixTrans()
                imageMatrix = currMatrix
                postOnAnimation(this)
            }
        }
    }
}

// SuperMin and SuperMax multipliers. Determine how much the image can be
// zoomed below or above the zoom boundaries, before animating back to the
// min/max zoom boundary.
private val SUPER_MIN_MULTIPLIER = .75f
private val SUPER_MAX_MULTIPLIER = 1.25f

private fun getFixTrans(trans: Float, viewSize: Float, contentSize: Float): Float {
    val (minTrans, maxTrans) = if (contentSize <= viewSize)
        Pair(0f, viewSize - contentSize) else
        Pair(viewSize - contentSize, 0f)
    return when {
        trans < minTrans -> -trans + minTrans
        trans > maxTrans -> -trans + maxTrans
        else -> 0f
    }
}

private fun getFixDragTrans(delta: Float, viewSize: Float, contentSize: Float): Float {
    return if (contentSize <= viewSize) 0f else delta
}

private fun computeViewSize(mode: Int, requestedSize: Int, drawableSize: Int): Int {
    return when (mode) {
        AT_MOST -> Math.min(drawableSize, requestedSize)
        UNSPECIFIED -> drawableSize
        EXACTLY -> requestedSize
        else -> throw IllegalArgumentException("Undefined measure specification mode " + mode)
    }
}
