package com.belotron.weatherradarhr.gifdecode

import android.graphics.Bitmap

interface Pixels {
    val height: Int
    fun get(x: Int, y: Int): Int
}

class IntArrayPixels(
        private val pixels: IntArray,
        private val width: Int
) : Pixels {

    override val height = pixels.size / width

    override fun get(x: Int, y: Int) = pixels[width * y + x]
}

class BitmapPixels(
        private val bitmap: Bitmap
) : Pixels {

    override val height = bitmap.height

    override fun get(x: Int, y: Int) = bitmap.getPixel(x, y)
}
