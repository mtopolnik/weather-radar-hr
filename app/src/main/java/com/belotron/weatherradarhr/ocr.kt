package com.belotron.weatherradarhr

import android.content.Context
import android.graphics.Bitmap
import java.util.Calendar
import java.util.Calendar.*
import java.util.TimeZone

fun initOcr(context : Context) {
    LradarOcr.initDigitBitmaps(context)
    KradarOcr.initDigitBitmaps(context)
}

object LradarOcr {

    private var digitBitmaps: List<Bitmap> = emptyList()

    fun initDigitBitmaps(context: Context) {
        if (digitBitmaps.isEmpty()) {
            digitBitmaps = context.loadDigits("lradar")
        }
    }

    fun ocrLradarTimestamp(bitmap: Bitmap): Long {
        val dt = ocrDateTime(bitmap)
        info { "OCRed date/time: $dt" }
        return dt.toTimestamp()
    }

    private fun ocrDateTime(lradar: Bitmap) = DateTime(
            year = readNumber(lradar, 0, 1, 2, 3),
            month = readNumber(lradar, 5, 6),
            day = readNumber(lradar, 8, 9),
            hour = readNumber(lradar, 11, 12),
            minute = readNumber(lradar, 14, 15))

    private fun readNumber(lradar: Bitmap, vararg indices : Int): Int =
            indices.fold(0, { acc, ind -> 10 * acc + readDigit(lradar, ind) })

    private fun readDigit(lradar: Bitmap, pos : Int) =
        (0..9).find { stripeEqual(lradar, 7 * pos + 9, 28, digitBitmaps[it], 0) } ?: 1
}

object KradarOcr {
    private var dateDigitBitmaps: List<Bitmap> = emptyList()
    private var timeDigitBitmaps: List<Bitmap> = emptyList()

    fun initDigitBitmaps(context: Context) {
        if (dateDigitBitmaps.isEmpty()) {
            dateDigitBitmaps = context.loadDigits("kradar/date")
        }
        if (timeDigitBitmaps.isEmpty()) {
            timeDigitBitmaps = context.loadDigits("kradar/time")
        }
    }

    fun ocrKradarTimestamp(bitmap: Bitmap): Long {
        val dt = ocrDateTime(bitmap)
        info { "OCRed date/time: $dt" }
        return dt.toTimestamp()
    }

    private fun ocrDateTime(bitmap: Bitmap): DateTime {
        val now = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

        // Remember that dt.month is 1-based, but now.get(MONTH) is 0-based!

        val dt = DateTime(
                year = readDateNumber(bitmap, 7, 8, 9, 10),
                month = now.get(MONTH) + 1, //readDateString(bitmap, 3, 4, 5),
                day = readDateNumber(bitmap, 0, 1),
                hour = readTimeNumber(bitmap, 486, 504),
                minute = readTimeNumber(bitmap, 532, 550),
                second = readTimeNumber(bitmap, 578, 596)
        )
        return when {
            dt.year < now.get(YEAR) -> DateTime(dt, month = 12)
            dt.day > now.get(DAY_OF_MONTH) -> DateTime(dt, month = dt.month - 1)
            else -> dt
        }
    }

    private fun readTimeNumber(bitmap: Bitmap, vararg xs : Int): Int =
        xs.fold(0, { acc, x -> 10 * acc + readTimeDigit(bitmap, x) })

    private fun readTimeDigit(bitmap: Bitmap, x : Int) =
            (0..13).find { stripeEqual(bitmap, x, 143, timeDigitBitmaps[it], 3) } ?: 1

    private fun readDateNumber(bitmap: Bitmap, vararg indices : Int): Int =
            indices.fold(0, { acc, i -> 10 * acc + readDateDigit(bitmap, i) })

    private fun readDateDigit(bitmap: Bitmap, pos: Int): Int {
        val imgX = 486 + 10 * pos
        val imgY = 168
        return when {
            (0 until 13).all { rectY -> bitmap.getPixel(imgX + 1, imgY + rectY) != -1 } -> 0 // blank space
            else -> (0..9).find { stripeEqual(bitmap, imgX, imgY, dateDigitBitmaps[it], 1) } ?: 1
        }
    }
}

private fun stripeData(img: Bitmap, imgX: Int, imgY: Int, rectHeight: Int, rectX: Int): List<Int> =
        (0 until rectHeight).map { rectY -> img.getPixel(imgX + rectX, imgY + rectY) }

/**
 * Returns true if the vertical stripe of `rect` at `rectX` is equal to
 * the vertical stripe in `img` at `(imgX + rectX, imgY)`
 */
private fun stripeEqual(img: Bitmap, imgX: Int, imgY: Int, rect: Bitmap, rectX: Int) =
    (0 until rect.height).all { rectY -> img.getPixel(imgX + rectX, imgY + rectY) == rect.getPixel(rectX, rectY) }

private fun Context.loadDigits(path: String) = (0..9).map { loadDigit(path, it) }

private fun Context.loadDigit(path: String, digit: Int) =
        assets.open("$path/$digit.gif").use { it.readBytes() }.toBitmap()

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
