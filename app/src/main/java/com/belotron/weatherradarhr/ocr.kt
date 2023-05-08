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

import android.content.Context
import com.belotron.weatherradarhr.gifdecode.BitmapPixels
import com.belotron.weatherradarhr.gifdecode.Pixels
import com.belotron.weatherradarhr.gifdecode.decodeArgbToGray
import java.util.*
import java.util.Calendar.*
import java.util.TimeZone.getTimeZone
import kotlin.math.abs

object SloOcr {

    @Volatile
    private var digitTemplates: List<Pixels> = emptyList()

    fun ocrSloTimestamp(pixels: Pixels): Long {
        initDigitPixelses()
        ocrDateTime(pixels).also {
            debug { "ARSO OCRed date/time: $it" }
            return it.toTimestamp
        }
    }

    private fun initDigitPixelses() {
        if (digitTemplates.isEmpty()) {
            digitTemplates = appContext.loadDigits("arso", "gif")
        }
    }

    private fun ocrDateTime(pixels: Pixels) = DateTime(
            year = readNumber(pixels, 0, 1, 2, 3),
            month = readNumber(pixels, 5, 6),
            day = readNumber(pixels, 8, 9),
            hour = readNumber(pixels, 11, 12),
            minute = readNumber(pixels, 14, 15),
            tz = getTimeZone("UTC"))

    private fun readNumber(pixels: Pixels, vararg indices: Int): Int =
            indices.fold(0) { acc, ind -> 10 * acc + readDigit(pixels, ind) }

    private fun readDigit(pixels: Pixels, pos: Int) =
            (0..9).find { stripeEqual(pixels, 7 * pos + 9, 28, digitTemplates[it], 0) } ?: ocrFailed()
}

object HrOcr {
    @Volatile
    private var digitTemplates: List<Pixels> = emptyList()

    fun ocrTimestampKompozit(pixels: Pixels) = ocrHrTimestamp(pixels, 6)

    fun ocrTimestampSingle(pixels: Pixels) = ocrHrTimestamp(pixels, 66)

    private fun ocrHrTimestamp(pixels: Pixels, imgY: Int): Long {
        initDigitPixelses()
        val dateTime = ocrDateTime(pixels, imgY)
        debug { "DHMZ OCRed date/time: $dateTime" }
        return dateTime.toTimestamp
    }

    private fun initDigitPixelses() = synchronized(this) {
        if (digitTemplates.isEmpty()) {
            digitTemplates = appContext.loadDigits("dhmz", "png")
        }
    }

    private fun ocrDateTime(img: Pixels, y: Int): DateTime {
        var x = 80
        val digits = IntArray(12) { 0 }
        var digitIndex = 0
        while (x < 260 && digitIndex < 12) {
            val digit = (0..9).find { isMatch(img, x, y, digitTemplates[it]) }
            if (digit != null) {
                digits[digitIndex++] = digit
                x += 6
            } else {
                x++
            }
        }
        return digits.run {
            DateTime(
                    year = digitsToInt(0..3),
                    month = digitsToInt(4..5),
                    day = digitsToInt(6..7),
                    hour = digitsToInt(8..9),
                    minute = digitsToInt(10..11),
                    tz = getTimeZone("Europe/Zagreb")
            )
        }
    }

    private fun isMatch(img: Pixels, imgX: Int, imgY: Int, template: Pixels): Boolean {
        var totalDiff = 0
//        info { "art\n" + asciiArt(img, imgX, imgY, template) }
        (0 until template.height).forEach { y ->
            (0 until template.width).forEach { x ->
                totalDiff += abs(decodeArgbToGray(img[imgX + x, imgY + y]) - decodeArgbToGray(template[x, y]))
                if (totalDiff > 2500) {
                    return false
                }
            }
        }
        return true
    }

    private fun IntArray.digitsToInt(range: IntRange) = range.fold(0) { acc, i -> 10 * acc + this[i] }
}

private fun asciiArt(img: Pixels, left: Int, top: Int, template: Pixels): String {
    val width = template.width
    val height = template.height
    val b = StringBuilder((2 * width + 2) * height)
    (0 until height).forEach { y ->
        (0 until width).forEach { x ->
            b.append(img.asciiPixel(left + x, top + y))
        }
        b.append("  ")
        (0 until width).forEach { x ->
            b.append(template.asciiPixel(x, y))
        }
        b.append('\n')
    }
    return b.toString()
}

/**
 * Returns true if the vertical stripe of `rect` at `rectX` is equal to
 * the vertical stripe in `img` at `(imgX + rectX, imgY)`
 */
private fun stripeEqual(img: Pixels, imgX: Int, imgY: Int, rect: Pixels, rectX: Int) =
        (0 until rect.height).all { rectY -> img[imgX + rectX, imgY + rectY] == rect[rectX, rectY] }

private fun Context.loadDigits(path: String, suffix: String) = (0..9).map { loadDigit(path, suffix, it) }

private fun Context.loadDigit(path: String, suffix: String, digit: Int): BitmapPixels =
        assets.open("$path/$digit.$suffix").use { it.readBytes() }.decodeToBitmap().asPixels()

class DateTime(
        copyFrom: DateTime? = null,
        year: Int? = null,
        month: Int? = null,
        day: Int? = null,
        hour: Int? = null,
        minute: Int? = null,
        second: Int? = null,
        private val tz: TimeZone
) {
    val year: Int = year ?: copyFrom?.year ?: throw missingVal("year")
    val month: Int = month ?: copyFrom?.month ?: throw missingVal("month")
    val day: Int = day ?: copyFrom?.day ?: throw missingVal("day")
    val hour: Int = hour ?: copyFrom?.hour ?: throw missingVal("hour")
    val minute: Int = minute ?: copyFrom?.minute ?: throw missingVal("minute")
    val second: Int = second ?: copyFrom?.second ?: 0

    val toTimestamp: Long get() {
        val cal = Calendar.getInstance(tz)
        cal.timeInMillis = 0
        cal.set(YEAR, year)
        cal.set(MONTH, month - 1)
        cal.set(DAY_OF_MONTH, day)
        cal.set(HOUR_OF_DAY, hour)
        cal.set(MINUTE, minute)
        cal.set(SECOND, second)
        return cal.timeInMillis
    }

    private fun missingVal(field: String) = IllegalArgumentException("$field missing")

    override fun toString(): String = "%4d-%02d-%02d %02d:%02d:%02d %s".format(
            year, month, day, hour, minute, second, tz)
}

private fun ocrFailed(): Int {
    throw RuntimeException("OCR failed")
}
