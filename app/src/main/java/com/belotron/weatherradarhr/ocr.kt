package com.belotron.weatherradarhr

import android.content.Context
import android.graphics.Bitmap
import com.belotron.weatherradarhr.gifdecode.IntArrayPixels
import com.belotron.weatherradarhr.gifdecode.Pixels
import com.belotron.weatherradarhr.gifdecode.decodeRgbaToGrey
import java.util.Calendar
import java.util.Calendar.DAY_OF_MONTH
import java.util.Calendar.HOUR_OF_DAY
import java.util.Calendar.MINUTE
import java.util.Calendar.MONTH
import java.util.Calendar.SECOND
import java.util.Calendar.YEAR
import java.util.TimeZone
import java.util.TimeZone.getTimeZone
import kotlin.math.abs

object LradarOcr {

    private var digitTemplates: List<Pixels> = emptyList()

    fun ocrLradarTimestamp(pixels: Pixels): Long {
        initDigitPixelses()
        with(ocrDateTime(pixels)) {
            debug { "ARSO OCRed date/time: $this" }
            return toTimestamp
        }
    }

    fun ocrLradarTimestamp(bitmap: Bitmap) = ocrLradarTimestamp(bitmap.asPixels())

    private fun initDigitPixelses() {
        if (digitTemplates.isEmpty()) {
            digitTemplates = appContext.loadDigits("lradar")
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

object KradarOcr {
    private var digitTemplates: List<IntArrayPixels> = emptyList()

    fun ocrKradarTimestamp(pixels: Pixels): Long {
        initDigitPixelses()
        with(ocrDateTime(pixels)) {
            debug { "DHMZ OCRed date/time: $this" }
            return toTimestamp
        }
    }

    fun ocrKradarTimestamp(bitmap: Bitmap) = ocrKradarTimestamp(bitmap.asPixels())

    private fun initDigitPixelses() {
        if (digitTemplates.isEmpty()) {
            digitTemplates = appContext.loadDigits("kradar")
            digitTemplates.forEach { it.decodeToGreyscale }
        }
    }

    private fun ocrDateTime(img: Pixels): DateTime {
        var x = 108
        val digits = IntArray(12) { 0 }
        var digitIndex = 0
        while (x < 230) {
            val digit = (0..9).find { isMatch(img, x, digitTemplates[it]) }
            if (digit != null) {
                digits[digitIndex++] = digit
                x += 7
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

    private fun isMatch(img: Pixels, imgX: Int, template: Pixels): Boolean {
        val imgY = 2
        var totalDiff = 0
        (0 until template.height).forEach { y ->
            (0 until template.width).forEach { x ->
                totalDiff += abs(decodeRgbaToGrey(img[imgX + x, imgY + y]) - template[x, y])
                if (totalDiff > 2000) {
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
        (0 until rect.height).all { rectY -> img.get(imgX + rectX, imgY + rectY) == rect.get(rectX, rectY) }

private fun Context.loadDigits(path: String) = (0..9).map { loadDigit(path, it) }

private fun Context.loadDigit(path: String, digit: Int) =
        assets.open("$path/$digit.gif").use { it.readBytes() }.parseGif().toPixels()

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
