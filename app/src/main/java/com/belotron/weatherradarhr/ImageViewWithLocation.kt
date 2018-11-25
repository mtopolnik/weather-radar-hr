package com.belotron.weatherradarhr

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Matrix.MSCALE_X
import android.graphics.Paint
import android.graphics.Paint.Style.STROKE
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH
import android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW
import android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
import android.os.Build
import android.util.AttributeSet
import android.widget.ImageView
import java.lang.Math.PI
import java.lang.Math.sin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val PREFERRED_LOCDOT_RADIUS_IMAGEPIXELS = 3
private const val MAX_LOCDOT_SCALE = 1.75f

open class ImageViewWithLocation
@JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0)
    : ImageView(context, attrs, defStyle
) {
    lateinit var mapShape: MapShape

    lateinit var locationState: LocationState
    private val location get() = locationState.location
    private val azimuth get() = locationState.azimuth
    private val azimuthAccuracyRating get() = locationState.azimuthAccuracy

    // Reusable value containers
    private val point = FloatArray(2)
    protected val mx = Matrix()
    protected val m = FloatArray(9)
    protected val rectF = RectF()

    private val locdotRadius = resources.getDimension(R.dimen.locdot_radius)
    private val flashlightRange = resources.getDimension(R.dimen.locdot_flashlight_range)
    private val ourColor = resources.getColor(R.color.locdot)
    private val dotPaint = Paint().apply { color = ourColor }
    private val flashlightPaint = Paint()
    private val borderThickness = resources.getDimension(R.dimen.locdot_border_thickness)
    private val whiteBorderPaint = Paint().apply {
        color = resources.getColor(android.R.color.white)
        style = STROKE
        strokeWidth = borderThickness
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
            val viewX = point[0]
            val viewY = point[1]
            val scale = run {
                val preferredLocdotRadius = m[MSCALE_X] * PREFERRED_LOCDOT_RADIUS_IMAGEPIXELS
                val preferredScale = preferredLocdotRadius / locdotRadius
                max(1f, min(MAX_LOCDOT_SCALE, preferredScale))
            }
            with(canvas) {
                scale(scale, scale, viewX, viewY)
                paintFlashlight(viewX, viewY)
                drawCircle(viewX, viewY, locdotRadius, dotPaint)
                drawCircle(viewX, viewY, locdotRadius - 2 * borderThickness, whiteBorderPaint)
            }
        }
    }

    private val flashlightColorStops = run {
        val transparent = 0x00ffffff
        val redTransparent = ourColor and 0xe0ffffffu.toInt()
        intArrayOf(transparent, transparent, redTransparent, transparent)
    }

    @SuppressLint("NewApi")
    private fun Canvas.paintFlashlight(imageX: Float, imageY: Float) {
        val flashlightRange = flashlightRange
        val bearingAccuracy = location
                ?.takeIf { Build.VERSION.SDK_INT >= 26 }
                ?.let { it.bearingAccuracyDegrees }
                ?: 0f
                .radians
        val azimuthAccuracy = when (azimuthAccuracyRating) {
            SENSOR_STATUS_ACCURACY_HIGH -> PI / 6
            SENSOR_STATUS_ACCURACY_MEDIUM -> PI / 2
            SENSOR_STATUS_ACCURACY_LOW -> 3 * PI / 4
            else -> 2 * PI
        }.toFloat()
        val (direction, spread) = if (bearingAccuracy != 0f && bearingAccuracy < PI / 2) {
            Pair(location!!.bearing, bearingAccuracy)
        } else {
            Pair(azimuth, azimuthAccuracy)
        }
        val dotRadius = locdotRadius
        val arcCenterDist = if (spread <= PI) dotRadius / sin(spread / 2.0).toFloat() else 0f
        val arcCenterX = imageX - arcCenterDist
        val arcRadius = flashlightRange + arcCenterDist
        val arcPortionBeforeTouchPoint =
                if (arcCenterDist > 0f) run {
                    val distToTangentPoint = sqrt(arcCenterDist * arcCenterDist - dotRadius * dotRadius)
                    distToTangentPoint / arcRadius
                } else 0f
        save()
        try {
            rotate(direction.degrees - 90, imageX, imageY)
            flashlightPaint.shader = RadialGradient(
                    arcCenterX,
                    imageY,
                    arcRadius,
                    flashlightColorStops,
                    floatArrayOf(0f, arcPortionBeforeTouchPoint, arcPortionBeforeTouchPoint,
                            if (spread <= PI) 1f else .45f),
                    Shader.TileMode.CLAMP
            )
            drawArc(
                    arcCenterX - arcRadius,
                    imageY - arcRadius,
                    arcCenterX + arcRadius,
                    imageY + arcRadius,
                    -spread.degrees / 2,
                    spread.degrees,
                    true,
                    flashlightPaint
            )
        } finally {
            restore()
        }
    }
}

