package com.belotron.weatherradarhr

import android.content.Context
import android.graphics.Bitmap
import com.belotron.weatherradarhr.gifdecode.Pixels
import java.util.Calendar
import java.util.Calendar.DAY_OF_MONTH
import java.util.Calendar.HOUR_OF_DAY
import java.util.Calendar.MINUTE
import java.util.Calendar.MONTH
import java.util.Calendar.SECOND
import java.util.Calendar.YEAR
import java.util.TimeZone

object LradarOcr {

    private var digitPixelses: List<Pixels> = emptyList()

    fun ocrLradarTimestamp(pixels: Pixels): Long {
        initDigitPixelses()
        val dt = ocrDateTime(pixels)
        debug { "ARSO OCRed date/time: $dt" }
        return dt.toTimestamp()
    }

    fun ocrLradarTimestamp(bitmap: Bitmap) = ocrLradarTimestamp(bitmap.asPixels())

    private fun initDigitPixelses() {
        if (digitPixelses.isEmpty()) {
            digitPixelses = appContext.loadDigits("lradar")
        }
    }

    private fun ocrDateTime(pixels: Pixels) = DateTime(
            year = readNumber(pixels, 0, 1, 2, 3),
            month = readNumber(pixels, 5, 6),
            day = readNumber(pixels, 8, 9),
            hour = readNumber(pixels, 11, 12),
            minute = readNumber(pixels, 14, 15))

    private fun readNumber(pixels: Pixels, vararg indices : Int): Int =
            indices.fold(0, { acc, ind -> 10 * acc + readDigit(pixels, ind) })

    private fun readDigit(pixels: Pixels, pos : Int) =
        (0..9).find { stripeEqual(pixels, 7 * pos + 9, 28, digitPixelses[it], 0) } ?: ocrFailed()
}

object KradarOcr {
    private var dateDigitPixelses: List<Pixels> = emptyList()
    private var timeDigitPixelses: List<Pixels> = emptyList()

    fun ocrKradarTimestamp(pixels: Pixels): Long {
        initDigitPixelses()
        val dt = ocrDateTime(pixels)
        debug { "DHMZ OCRed date/time: $dt" }
        return dt.toTimestamp()
    }

    fun ocrKradarTimestamp(bitmap: Bitmap) = ocrKradarTimestamp(bitmap.asPixels())

    private fun initDigitPixelses() {
        if (dateDigitPixelses.isEmpty()) {
            dateDigitPixelses = appContext.loadDigits("kradar/date")
        }
        if (timeDigitPixelses.isEmpty()) {
            timeDigitPixelses = appContext.loadDigits("kradar/time")
        }
    }

    private fun ocrDateTime(pixels: Pixels): DateTime {
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

        // Remember that dt.month is 1-based, but now.get(MONTH) is 0-based!

        val dt = DateTime(
                year = readDateNumber(pixels, 7, 8, 9, 10),
                month = now.get(MONTH) + 1, //readDateString(bitmap, 3, 4, 5),
                day = readDateNumber(pixels, 0, 1),
                hour = readTimeNumber(pixels, 486, 504),
                minute = readTimeNumber(pixels, 532, 550),
                second = readTimeNumber(pixels, 578, 596)
        )
        return when {
            dt.year < now.get(YEAR) -> DateTime(dt, month = 12)
            dt.day > now.get(DAY_OF_MONTH) -> DateTime(dt, month = dt.month - 1)
            else -> dt
        }
    }

    private fun readTimeNumber(pixels: Pixels, vararg xs : Int): Int =
        xs.fold(0, { acc, x -> 10 * acc + readTimeDigit(pixels, x) })

    private fun readTimeDigit(pixels: Pixels, x : Int) =
            (0..9).find { stripeEqual(pixels, x, 143, timeDigitPixelses[it], 3) } ?: ocrFailed()

    private fun readDateNumber(pixels: Pixels, vararg indices : Int): Int =
            indices.fold(0, { acc, i -> 10 * acc + readDateDigit(pixels, i) })

    private fun readDateDigit(pixels: Pixels, pos: Int): Int {
        val imgX = 486 + 10 * pos
        val imgY = 168
        return when {
            (0 until 13).all { rectY -> pixels.get(imgX + 1, imgY + rectY) != -1 } -> 0 // blank space
            else -> (0..9).find { stripeEqual(pixels, imgX, imgY, dateDigitPixelses[it], 1) } ?: ocrFailed()
        }
    }
}

private fun stripeData(img: Pixels, imgX: Int, imgY: Int, rectHeight: Int, rectX: Int): List<Int> =
        (0 until rectHeight).map { rectY -> img.get(imgX + rectX, imgY + rectY) }

/**
 * Returns true if the vertical stripe of `rect` at `rectX` is equal to
 * the vertical stripe in `img` at `(imgX + rectX, imgY)`
 */
private fun stripeEqual(img: Pixels, imgX: Int, imgY: Int, rect: Pixels, rectX: Int) =
    (0 until rect.height).all { rectY -> img.get(imgX + rectX, imgY + rectY) == rect.get(rectX, rectY) }

private fun Context.loadDigits(path: String) = (0..9).map { loadDigit(path, it) }

private fun Context.loadDigit(path: String, digit: Int) =
        assets.open("$path/$digit.gif").use { it.readBytes() }.toPixels()

class DateTime(
        copyFrom: DateTime? = null,
        year: Int? = null,
        month: Int? = null,
        day: Int? = null,
        hour: Int? = null,
        minute: Int? = null,
        second: Int? = null
) {
    val year: Int = year ?: copyFrom?.year ?: throw missingVal("year")
    val month: Int = month ?: copyFrom?.month ?: throw missingVal("month")
    val day: Int = day ?: copyFrom?.day ?: throw missingVal("day")
    val hour: Int = hour ?: copyFrom?.hour ?: throw missingVal("hour")
    val minute: Int = minute ?: copyFrom?.minute ?: throw missingVal("minute")
    val second: Int = second ?: copyFrom?.second ?: 0

    fun toTimestamp() : Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
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

    override fun toString(): String = "%4d-%02d-%02d %02d:%02d:%02d UTC".format(
            year, month, day, hour, minute, second)
}

private fun ocrFailed(): Int {
    throw RuntimeException("OCR failed")
}
