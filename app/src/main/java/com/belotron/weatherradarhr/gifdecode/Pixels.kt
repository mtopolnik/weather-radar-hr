package com.belotron.weatherradarhr.gifdecode

import android.graphics.Bitmap
import androidx.core.graphics.get
import androidx.core.graphics.set
import kotlin.math.min

private const val ASCII_GREYSCALE = "@%#*+=-:. "
private const val MAX_PIXEL_VALUE = (3 * 0xff)

fun decodeRgbaToGrey(argb: Int): Int {
    val byteMask = 0xff
    val leftR = argb and byteMask
    val leftG = argb shr 8 and byteMask
    val leftB = argb shr 16 and byteMask
    return leftR + leftG + leftB
}

interface Pixels {
    val width: Int
    val height: Int
    operator fun get(x: Int, y: Int): Int

    fun asciiPixel(x: Int, y: Int): Char {
        return ASCII_GREYSCALE[
                min(ASCII_GREYSCALE.length - 1,
                        decodeRgbaToGrey(this[x, y]) * ASCII_GREYSCALE.length / MAX_PIXEL_VALUE)
        ]
    }

    fun convertToGreyscale()
}

class IntArrayPixels(
        private val pixels: IntArray,
        override val width: Int
) : Pixels {
    override val height = pixels.size / width
    override operator fun get(x: Int, y: Int) = pixels[width * y + x]

    override fun convertToGreyscale() = pixels.forEachIndexed { i, it -> pixels[i] = decodeRgbaToGrey(it) }
}

class BitmapPixels(
        private val bitmap: Bitmap
) : Pixels {
    override val width = bitmap.width
    override val height = bitmap.height
    override operator fun get(x: Int, y: Int) = bitmap[x, y]

    override fun convertToGreyscale() {
        for (y in 0..height) {
            for (x in 0..width) {
                bitmap[x, y] = decodeRgbaToGrey(bitmap[x, y])
            }
        }
    }
}
