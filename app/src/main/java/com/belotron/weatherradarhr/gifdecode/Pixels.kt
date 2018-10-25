package com.belotron.weatherradarhr.gifdecode

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.min

private const val ASCII_GREYSCALE = "@%#*+=-:. "
private const val MAX_PIXEL_VALUE = (3 * 0xff)

fun pixelValue(argb: Int): Int {
    val byteMask = 0xff
    val leftR = argb and byteMask
    val leftG = argb shr 8 and byteMask
    val leftB = argb shr 16 and byteMask
    return leftR + leftG + leftB
}

fun pixelDiff(left: Int, right: Int): Int {
    return abs(pixelValue(left) - pixelValue(right))
}

interface Pixels {
    val width: Int
    val height: Int
    operator fun get(x: Int, y: Int): Int

    fun asciiPixel(x: Int, y: Int): Char {
        return ASCII_GREYSCALE[
                min(ASCII_GREYSCALE.length - 1,
                        pixelValue(this[x, y]) * ASCII_GREYSCALE.length / MAX_PIXEL_VALUE)
        ]
    }
}

class IntArrayPixels(
        private val pixels: IntArray,
        override val width: Int
) : Pixels {
    override val height = pixels.size / width
    override operator fun get(x: Int, y: Int) = pixels[width * y + x]
}

class BitmapPixels(
        private val bitmap: Bitmap
) : Pixels {
    override val width = bitmap.width
    override val height = bitmap.height
    override operator fun get(x: Int, y: Int) = bitmap.getPixel(x, y)
}
