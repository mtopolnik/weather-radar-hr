package com.belotron.weatherradarhr

import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.animation.ValueAnimator.INFINITE
import android.animation.ValueAnimator.REVERSE
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.location.Location
import android.util.AttributeSet
import android.widget.ImageView

open class ImageViewWithLocation
@JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0)
    : ImageView(context, attrs, defStyle
) {
    lateinit var mapShape: MapShape
    var location: Location? = null

    private val point = FloatArray(2)
    protected val mx = Matrix()

    private val paint = Paint().apply {
        color = resources.getColor(android.R.color.holo_red_light)
        strokeWidth = 10f
    }

//    init {
//        animate(PropertyValuesHolder.ofFloat("latitude", 45f, 47f)) { anim ->
//            latitude = (anim.getAnimatedValue("latitude") as Float).toDouble()
//        }
//        animate(PropertyValuesHolder.ofFloat("longitude", 14f, 19f)) { anim ->
//            longitude = (anim.getAnimatedValue("longitude") as Float).toDouble()
//        }
//    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        location?.also { location ->
            mapShape.locationToPixel(location, point)
            matrix.mapPoints(point)
            imageMatrix.mapPoints(point)
            canvas.drawCircle(point[0], point[1], 10f, paint)
        }
    }

    private fun animate(pvh: PropertyValuesHolder, updateListener: (ValueAnimator) -> Unit) {
        with(ValueAnimator.ofPropertyValuesHolder(pvh)) {
            duration = 2000
            repeatCount = INFINITE
            repeatMode = REVERSE
            addUpdateListener(updateListener)
            addUpdateListener { invalidate() }
            start()
        }
    }
}
