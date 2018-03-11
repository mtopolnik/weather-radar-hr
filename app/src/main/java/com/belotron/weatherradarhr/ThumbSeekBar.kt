package com.belotron.weatherradarhr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.widget.SeekBar
import android.text.TextPaint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface


class ThumbSeekBar(context : Context, attrs: AttributeSet) : SeekBar(context, attrs) {
    var thumbText: String = ""
    var thumbProgress: Int = 0

    private val thumbHeight = resources.getDimensionPixelOffset(R.dimen.seekbar_thumb_height).toFloat()
    private val textBorder = resources.getDimensionPixelOffset(R.dimen.seekbar_thumb_text_border).toFloat()
    private val textBounds = Rect()
    private val textRect = RectF()
    private val textPaint = TextPaint().apply {
        color = resources.getColor(R.color.seekbarThumbText)
        textSize = resources.getDimensionPixelSize(R.dimen.thumb_text_size).toFloat()
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.LEFT
    }

    private val textBackgroundPaint = Paint().apply {
        color = resources.getColor(R.color.seekbarBackground)
    }


    @Synchronized
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (thumbText.isEmpty()) {
            return
        }
        textPaint.getTextBounds(thumbText, 0, thumbText.length, textBounds)
        val progressRatio = thumbProgress.toFloat() / max
        val netWidth = width - (paddingLeft + paddingRight)
        val thumbX = paddingLeft + progressRatio * netWidth - textBounds.width() / 2f
        textRect.set(
                thumbX - textBorder,
                -thumbHeight - textBounds.height() - textBorder,
                thumbX + textBounds.width() + textBorder,
                -thumbHeight + textBorder)
        canvas.drawRect(textRect, textBackgroundPaint)
        canvas.drawText(thumbText, thumbX, -thumbHeight, textPaint)
    }
}
