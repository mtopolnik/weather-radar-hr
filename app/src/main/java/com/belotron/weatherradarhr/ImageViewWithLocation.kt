package com.belotron.weatherradarhr

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.location.Location
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import kotlin.math.max
import kotlin.properties.Delegates.observable
import kotlin.reflect.KProperty

private const val LOCDOT_SIZE_IMAGEPIXELS = 3
private const val STROBE_SIZE_IMAGEPIXELS = 50
private const val COMPASS_SIZE_IMAGEPIXELS = 9

open class ImageViewWithLocation
@JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0)
    : ImageView(context, attrs, defStyle
) {
    lateinit var mapShape: MapShape

    var location by observable(null as Location?) { _, _, _ -> invalidate() }
    var azimuth by observable(0f, ::invalidateIfGotLocation)
    var azimuthAccuracy by observable(0, ::invalidateIfGotLocation)

    private fun <T: Any> invalidateIfGotLocation(prop: KProperty<*>, old: T, new: T) {
        location?.also { invalidate() }
    }

    // Reusable value containers
    private val point = FloatArray(2)
    protected val mx = Matrix()
    protected val m = FloatArray(9)
    protected val rectF = RectF()

    private var strobeScale = 1f

    private val dotPaint = Paint().apply {
        color = resources.getColor(R.color.locdot)
    }
    private val strobePaint = Paint().apply {
        color = resources.getColor(R.color.locdot_strobe)
    }
    private val compassPaint = Paint().apply {
        color = resources.getColor(R.color.locdot)
    }

    fun animateStrobe() {
        with(ValueAnimator.ofPropertyValuesHolder(
                PropertyValuesHolder.ofFloat("opacity",
                        1f, 0.563f, 0.360f, 0.250f, 0.184f, 0.141f, 0.111f, 0.090f, 0.074f, 0.063f, 0.053f,
                        0.046f, 0.040f, 0.035f, 0.031f, 0.028f, 0.025f, 0.023f, 0.020f, 0.019f, 0.017f),
                PropertyValuesHolder.ofFloat("radius", 0f, 1f))
        ) {
            duration = 1000
            interpolator = LinearInterpolator()
            addUpdateListener {
                strobeScale = it.getAnimatedValue("radius") as Float
                val opacity = it.getAnimatedValue("opacity") as Float
                strobePaint.alpha = (255 * opacity).toInt()
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator?) {
                    postDelayed( { animateStrobe() }, 2000)
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        location?.also { location ->
            mapShape.locationToPixel(location, point)
            with(mx) {
                set(matrix)
                postConcat(imageMatrix)
                getValues(m)
                mapPoints(point)
            }
            val imgScale = m[Matrix.MSCALE_X]
            with(canvas) {
                concat(imageMatrix)
                val locdotRadius = max(resources.getDimension(R.dimen.locdot_min_size),
                        LOCDOT_SIZE_IMAGEPIXELS * imgScale)
                val strobeSize = max(resources.getDimension(R.dimen.locdot_strobe_min_size),
                        STROBE_SIZE_IMAGEPIXELS * imgScale)
                drawCircle(point[0], point[1],
                        locdotRadius + strobeScale * strobeSize,
                        strobePaint)
                drawCircle(point[0], point[1],
                        locdotRadius,
                        dotPaint)
                mapShape.locationToPixel(location, point)
//                paintCompass()
            }
        }
    }

    private fun Canvas.paintCompass() {
        val imgScale = m[Matrix.MSCALE_X]
        val arcSize = max(resources.getDimension(R.dimen.locdot_compass_min_size),
                COMPASS_SIZE_IMAGEPIXELS * imgScale)
        compassPaint.strokeWidth = arcSize / 3
        val angle = Math.PI / 6
        val arcCenterDist = LOCDOT_SIZE_IMAGEPIXELS / Math.sin(angle / 2)
        val arcCenterY = point[1] + arcCenterDist
        rectF.left = point[0]
        save()
        try {
            rotate(azimuth.degrees)
            drawArc(

            )
        } finally {
            restore()
        }
    }
}

private val Float.degrees get() = Math.toDegrees(this.toDouble()).toFloat()

