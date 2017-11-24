package com.belotron.weatherradarhr

import android.content.Context
import android.graphics.Bitmap
import java.util.Calendar
import java.util.Calendar.*
import java.util.TimeZone

object LRadarOCR {
    private var digitBitmaps: List<Bitmap> = emptyList()

    fun initDigitBitmaps(context: Context) {
        if (digitBitmaps.isEmpty()) {
            digitBitmaps = (0 .. 9).map { context.assets.open("""$it.gif""").use { it.readBytes() }.toBitmap() }
        }
    }

    fun ocrLradarTimestamp(lradar: Bitmap): Long {
        val dt = ocrDateTime(lradar)
        MyLog.i("""OCRed date/time: $dt""")
        val (year, month, day, hour, minute) = dt
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = 0
        cal.set(YEAR, year)
        cal.set(MONTH, month - 1)
        cal.set(DAY_OF_MONTH, day)
        cal.set(HOUR_OF_DAY, hour)
        cal.set(MINUTE, minute)
        return cal.timeInMillis
    }

    private fun ocrDateTime(lradar: Bitmap) = DateTime(
            year = readNumber(lradar, 0, 1, 2, 3),
            month = readNumber(lradar, 5, 6),
            day = readNumber(lradar, 8, 9),
            hour = readNumber(lradar, 11, 12),
            minute = readNumber(lradar, 14, 15))

    private data class DateTime(
            val year : Int,
            val month : Int,
            val day : Int,
            val hour : Int,
            val minute : Int
    )

    private fun readNumber(lradar: Bitmap, vararg indices : Int): Int =
            indices.fold(0, { acc, ind -> 10 * acc + readDigit(lradar, ind) })

    private fun readDigit(lradar: Bitmap, pos : Int) : Int {
        return (0 .. 9).find { stripeEqual(lradar, digitBitmaps[it], 7 * pos + 9, 28) }
                ?: throw AssertionError("""Couldn't read the digit at $pos""")
    }

    /**
     * Returns true if the left vertical stripe of `rect` is equal to
     * the vertical stripe in `lradar` at `(x0, y0)`
     */
    private fun stripeEqual(lradar: Bitmap, rect: Bitmap, x0 : Int, y0 : Int) =
            (0 until rect.height).all { y -> lradar.getPixel(x0, y0 + y) == rect.getPixel(0, y) }
}
