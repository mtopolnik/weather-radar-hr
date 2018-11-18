package com.belotron.weatherradarhr

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH
import android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW
import android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import java.lang.Math.PI
import java.lang.Math.sin
import java.lang.Math.toDegrees
import kotlin.math.sqrt

private const val LOCDOT_SIZE_IMAGEPIXELS = 3f
private const val STROBE_SIZE_IMAGEPIXELS = 50f
private const val COMPASS_SIZE_IMAGEPIXELS = 18f

open class ImageViewWithLocation
@JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0)
    : ImageView(context, attrs, defStyle
) {
    lateinit var mapShape: MapShape

    lateinit var locationState: LocationState
    private val location get() = locationState.location
    private val azimuth get() = locationState.azimuth
    private val azimuthAccuracy get() = locationState.azimuthAccuracy

    // Reusable value containers
    private val point = FloatArray(2)
    protected val mx = Matrix()
    protected val m = FloatArray(9)
    protected val rectF = RectF()

    private var strobeScale = 1f

    private val ourColor = resources.getColor(R.color.locdot)
    private val dotPaint = Paint().apply { color = ourColor }
    private val strobePaint = Paint().apply { color = ourColor; alpha = 0 }
    private val compassPaint = Paint().apply { color = ourColor }

    fun animateStrobe() {
        with(ValueAnimator.ofPropertyValuesHolder(
                PropertyValuesHolder.ofFloat("opacity",
                        1f, 0.563f, 0.360f, 0.250f, 0.184f, 0.141f, 0.111f, 0.090f, 0.074f, 0.063f, 0.053f,
                        0.046f, 0.040f, 0.035f, 0.031f, 0.028f, 0.025f, 0.023f, 0.020f, 0.019f, 0.017f, 0f),
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
            val imageX = point[0]
            val imageY = point[1]
            with(mx) {
                set(matrix)
                postConcat(imageMatrix)
                getValues(m)
            }
            with(canvas) {
                concat(imageMatrix)
                val ratioToMinSize = (100 * m[Matrix.MSCALE_X] / resources.getDimension(R.dimen.hundred_dp))
                if (ratioToMinSize < 1) {
                    scale(1 / ratioToMinSize, 1 / ratioToMinSize, imageX, imageY)
                }
                val locdotRadius = LOCDOT_SIZE_IMAGEPIXELS
                val strobeSize = STROBE_SIZE_IMAGEPIXELS
                drawCircle(imageX, imageY,
                        locdotRadius + strobeScale * strobeSize,
                        strobePaint)
                drawCircle(imageX, imageY,
                        locdotRadius,
                        dotPaint)
                paintCompass(imageX, imageY)
            }
        }
    }

    private fun Canvas.paintCompass(imageX: Float, imageY: Float) {
        val flashlightRange = COMPASS_SIZE_IMAGEPIXELS
        val angle = when (azimuthAccuracy) {
            SENSOR_STATUS_ACCURACY_HIGH -> PI / 6
            SENSOR_STATUS_ACCURACY_MEDIUM -> PI / 2
            SENSOR_STATUS_ACCURACY_LOW -> 3 * PI / 4
            else -> 2 * PI
        }.toFloat()
        val dotRadius = LOCDOT_SIZE_IMAGEPIXELS
        val arcCenterDist = if (angle <= PI) dotRadius / sin(angle / 2.0).toFloat() else 0f
        val arcCenterX = imageX - arcCenterDist
        val arcRadius = flashlightRange + arcCenterDist
        val arcPortionBeforeTouchPoint =
                if (arcCenterDist > 0f) run {
                    val distToTangentPoint = sqrt(arcCenterDist * arcCenterDist - dotRadius * dotRadius)
                    distToTangentPoint / arcRadius
                } else 0f
        save()
        try {
            rotate(azimuth.degrees - 90, imageX, imageY)
            with(compassPaint) {
                val transparent = 0x00ffffff
                shader = RadialGradient(
                        arcCenterX,
                        imageY,
                        arcRadius,
                        intArrayOf(transparent, transparent, ourColor, transparent),
                        floatArrayOf(0f, arcPortionBeforeTouchPoint, arcPortionBeforeTouchPoint,
                                if (angle <= PI) 1f else .4f),
                        Shader.TileMode.CLAMP
                )
            }
            drawArc(
                    arcCenterX - arcRadius,
                    imageY - arcRadius,
                    arcCenterX + arcRadius,
                    imageY + arcRadius,
                    -angle.degrees / 2,
                    angle.degrees,
                    true,
                    compassPaint
            )
        } finally {
            restore()
        }
    }
}

private val Float.degrees get() = toDegrees(this.toDouble()).toFloat()

