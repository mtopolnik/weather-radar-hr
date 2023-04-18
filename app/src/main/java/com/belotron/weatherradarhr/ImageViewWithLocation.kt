/*
 * Copyright (C) 2018-2023 Marko Topolnik
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
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
import android.hardware.SensorManager.*
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import java.lang.Math.PI
import java.lang.Math.sin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private const val PREFERRED_LOCDOT_RADIUS_METERS = 1_500
private const val MAX_LOCDOT_SCALE = 1.75f
private const val COLORMASK_TRANSPARENT = 0x00_ff_ff_ff
@ExperimentalUnsignedTypes
private val COLORMASK_88_PERCENT_OPACITY = 0xe0_ff_ff_ff_u.toInt()

open class ImageViewWithLocation
@JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0)
    : AppCompatImageView(context, attrs, defStyle
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
    private val ourColor = context.getColorCompat(R.color.locdot)
    private val dotPaint = Paint().apply { color = ourColor }
    private val flashlightPaint = Paint()
    private val borderThickness = resources.getDimension(R.dimen.locdot_border_thickness)
    private val whiteBorderPaint = Paint().apply {
        color = context.getColorCompat(android.R.color.white)
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
                val preferredLocdotRadius = m[MSCALE_X] * PREFERRED_LOCDOT_RADIUS_METERS / mapShape.pixelSizeMeters
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

    @ExperimentalUnsignedTypes
    private val flashlightColorStops = run {
        val transparent = COLORMASK_TRANSPARENT
        val redTransparent = ourColor and COLORMASK_88_PERCENT_OPACITY
        intArrayOf(transparent, transparent, redTransparent, transparent)
    }

    @SuppressLint("NewApi")
    @OptIn(ExperimentalUnsignedTypes::class)
    private fun Canvas.paintFlashlight(imageX: Float, imageY: Float) {
        val pi = PI.toFloat()
        val flashlightRange = flashlightRange
        val spread = when (azimuthAccuracyRating) {
            SENSOR_STATUS_ACCURACY_HIGH ->   1 * pi / 6
            SENSOR_STATUS_ACCURACY_MEDIUM -> 3 * pi / 6
            SENSOR_STATUS_ACCURACY_LOW ->    5 * pi / 6
            else -> 2 * pi
        }.toFloat()
        val dotRadius = locdotRadius
        val arcCenterDist = if (spread <= pi) dotRadius / sin(spread / 2.0).toFloat() else 0f
        val arcCenterX = imageX - arcCenterDist
        val arcRadius = flashlightRange + arcCenterDist
        val arcPortionBeforeTouchPoint =
                if (arcCenterDist > 0f) run {
                    val distToTangentPoint = sqrt(arcCenterDist * arcCenterDist - dotRadius * dotRadius)
                    distToTangentPoint / arcRadius
                } else 0f
        save()
        try {
            rotate(azimuth.toDegrees - 90, imageX, imageY)
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
                    -spread.toDegrees / 2,
                    spread.toDegrees,
                    true,
                    flashlightPaint
            )
        } finally {
            restore()
        }
    }
}

