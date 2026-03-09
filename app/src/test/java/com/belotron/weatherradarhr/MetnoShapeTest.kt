package com.belotron.weatherradarhr

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToInt

class MetnoShapeTest {

    // Reuse a single FloatArray to keep tests simple
    private val point = FloatArray(2)

    private fun pixelAt(lat: Double, lon: Double): Pair<Int, Int> {
        metnoShape.locationToPixel(lat, lon, point)
        return point[0].roundToInt() to point[1].roundToInt()
    }

    // Reference points used to fit the projection parameters
    @Test fun reykjavik()  = assertPixel(lat = 64.148, lon = -22.024, x = 578,  y = 154)
    @Test fun alexandria() = assertPixel(lat = 31.190, lon =  29.831, x = 1127, y = 561)
    @Test fun lisbon()     = assertPixel(lat = 38.705, lon =  -9.119, x = 595,  y = 543)
    @Test fun zagreb()     = assertPixel(lat = 45.811, lon =  16.000, x = 874,  y = 418)
    @Test fun london()     = assertPixel(lat = 51.494, lon =  -0.103, x = 705,  y = 357)

    // Add your own coordinates here to see the pixel location:
    // @Test fun myCity() = assertPixel(lat = ..., lon = ..., x = ..., y = ...)

    private fun assertPixel(lat: Double, lon: Double, x: Int, y: Int, delta: Int = 5) {
        val (actualX, actualY) = pixelAt(lat, lon)
        println("pixel at ($lat, $lon) = ($actualX, $actualY)")
        assertEquals("x", x.toDouble(), actualX.toDouble(), delta.toDouble())
        assertEquals("y", y.toDouble(), actualY.toDouble(), delta.toDouble())
    }
}
