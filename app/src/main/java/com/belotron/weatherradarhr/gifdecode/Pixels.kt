package com.belotron.weatherradarhr.gifdecode

import android.graphics.Bitmap
import androidx.core.graphics.get
import androidx.core.graphics.set
import kotlin.math.min

private const val ASCII_GRAYSCALE = "@%#*+=-:. "
private const val MAX_PIXEL_VALUE = (3 * 0xff)

fun decodeArgbToGray(argb: Int): Int {
    val byteMask = 0xff
    val r = argb shr 16 and byteMask
    val g = argb shr 8 and byteMask
    val b = argb and byteMask
    return r + g + b
}

interface Pixels {
    val width: Int
    val height: Int
    operator fun get(x: Int, y: Int): Int

    fun asciiPixel(x: Int, y: Int): Char {
        return ASCII_GRAYSCALE[
                min(ASCII_GRAYSCALE.length - 1,
                        decodeArgbToGray(this[x, y]) * ASCII_GRAYSCALE.length / MAX_PIXEL_VALUE)
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
    override operator fun get(x: Int, y: Int) = bitmap[x, y]
}
