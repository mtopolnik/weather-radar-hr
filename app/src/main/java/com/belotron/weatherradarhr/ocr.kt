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
            digitBitmaps = loadDigits(context, "lradar")
        }
    }

    fun ocrLradarTimestamp(bitmap: Bitmap): Long {
        val dt = ocrDateTime(bitmap)
        MyLog.i("""OCRed date/time: $dt""")
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
        (0..9).find { stripeEqual(lradar, 7 * pos + 9, 28, digitBitmaps[it], 0) }
                ?: throw AssertionError("""Couldn't read the digit at $pos""")
}

object KradarOcr {
    private var dateDigitBitmaps: List<Bitmap> = emptyList()
    private var timeDigitBitmaps: List<Bitmap> = emptyList()

    fun initDigitBitmaps(context: Context) {
//        if (dateDigitBitmaps.isEmpty()) {
//            dateDigitBitmaps = loadDigits(context, "kradar/date")
//        }
        if (timeDigitBitmaps.isEmpty()) {
            timeDigitBitmaps = loadDigits(context, "kradar/time")
        }
    }

    fun ocrKradarTimestamp(kradar: Bitmap): Long {
        val dt = ocrDateTime(kradar)
        MyLog.i("""OCRed date/time: $dt""")
        return dt.toTimestamp()
    }

    private fun ocrDateTime(kradar: Bitmap): DateTime {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val dt = DateTime(
                hour = readTimeNumber(kradar, 486, 504),
                minute = readTimeNumber(kradar, 532, 550),
                second = readTimeNumber(kradar, 578, 596),
                day = cal.get(DAY_OF_MONTH), //readDateNumber(kradar, 0, 1),
                month = cal.get(MONTH) + 1, //readDateString(kradar, 3, 4, 5),
                year = cal.get(YEAR) //readDateNumber(kradar, 7, 8, 9, 10)
        )
        if (dt.hour <= cal.get(HOUR_OF_DAY)) {
            return dt
        }
        cal.add(DAY_OF_MONTH, -1)
        return DateTime(
                hour = dt.hour,
                minute = dt.minute,
                second = dt.second,
                day = cal.get(DAY_OF_MONTH),
                month = cal.get(MONTH) + 1,
                year = cal.get(YEAR)
        )
    }

    private fun readTimeNumber(kradar: Bitmap, vararg xs : Int): Int =
        xs.fold(0, { acc, x -> 10 * acc + readTimeDigit(kradar, x) })

    private fun readTimeDigit(kradar: Bitmap, x : Int) =
            (0..9).find { stripeEqual(kradar, x, 143, timeDigitBitmaps[it], 3) }
                    ?: throw AssertionError("""Couldn't read the digit at $x""")

    private fun readDateNumber(kradar: Bitmap, vararg indices : Int): Int = TODO()

    private fun readDateString(kradar: Bitmap, vararg indices : Int): Int = TODO()

}

/**
 * Returns true if the vertical stripe of `rect` at `rectX` is equal to
 * the vertical stripe in `img` at `(imgX + rectX, imgY)`
 */
private fun stripeEqual(img: Bitmap, imgX: Int, imgY: Int, rect: Bitmap, rectX: Int) =
        (0 until rect.height).all { rectY -> img.getPixel(imgX + rectX, imgY + rectY) == rect.getPixel(rectX, rectY) }

private fun loadDigits(context: Context, path : String) =
        (0..9).map { context.assets.open("""$path/$it.gif""").use { it.readBytes() }.toBitmap() }

data class DateTime(
        val year : Int,
        val month : Int,
        val day : Int,
        val hour : Int,
        val minute : Int,
        val second : Int = 0
) {
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

    override fun toString(): String = "%4d-%02d-%02d %02d:%02d:%02d UTC".format(year, month, day, hour, minute, second)
}
