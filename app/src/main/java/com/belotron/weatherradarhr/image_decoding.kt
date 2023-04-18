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

import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat.PNG
import android.graphics.Bitmap.CompressFormat.WEBP_LOSSLESS
import android.graphics.BitmapFactory
import android.os.Build
import com.belotron.weatherradarhr.gifdecode.Allocator
import java.io.ByteArrayOutputStream

interface Frame {
    val timestamp: Long
}

interface FrameSequence<T : Frame> {
    val frames: MutableList<T>
    fun intoDecoder(allocator: Allocator): FrameDecoder<T>
}

interface FrameDecoder<T : Frame> {
    val sequence: FrameSequence<T>
    fun getBitmap(frameIndex: Int): Bitmap
}

val <T : Frame> FrameDecoder<T>.frameCount: Int get() = sequence.frames.size

class PngFrame(
    private val pngBytes: ByteArray,
    override val timestamp: Long
) : Frame {
    private val width: Int
    private val height: Int

    init {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds }
        BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, opts)
        opts.apply {
            if (outWidth <= 0 || outHeight <= 0) {
                throw ImageDecodeException("Image has no pixels. width: $outWidth height: $outHeight")
            }
            width = outWidth
            height = outHeight
        }
    }

    fun decode(allocator: Allocator): Bitmap {
        return BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, BitmapFactory.Options().apply {
            inMutable = true
            inBitmap = allocator.obtain(width, height, Bitmap.Config.ARGB_8888)
        })
    }
}

class PngSequence(
    override val frames: MutableList<PngFrame>,
) : FrameSequence<PngFrame> {
    override fun intoDecoder(allocator: Allocator) = PngDecoder(allocator, this)
}

class PngDecoder(
    private val allocator: Allocator,
    override val sequence: PngSequence,
) : FrameDecoder<PngFrame> {

    override fun getBitmap(frameIndex: Int): Bitmap = sequence.frames[frameIndex].decode(allocator)
}

fun Bitmap.toCompressedBytes(): ByteArray =
    ByteArrayOutputStream().use {
        val compressFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) WEBP_LOSSLESS else PNG
        compress(compressFormat, 0, it)
        it.toByteArray()
    }
