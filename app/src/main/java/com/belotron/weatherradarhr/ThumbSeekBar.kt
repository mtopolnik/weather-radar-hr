package com.belotron.weatherradarhr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.AttributeSet
import android.widget.SeekBar


class ThumbSeekBar(context : Context, attrs: AttributeSet) : SeekBar(context, attrs) {
    var thumbText: String = ""
    var thumbProgress: Int = 0

    private val thumbHeight = resources.getDimensionPixelOffset(R.dimen.seekbar_thumb_height).toFloat()
    private val boxBorder = resources.getDimensionPixelOffset(R.dimen.seekbar_thumb_box_border).toFloat()
    private val textOffset = resources.getDimensionPixelOffset(R.dimen.seekbar_thumb_text_offset).toFloat()
    private val rectCornerRadius = resources.getDimensionPixelOffset(R.dimen.seekbar_thumb_box_corner_radius).toFloat()
    private val textBounds = Rect()
    private val textRect = RectF()
    private val trianglePath = Path()
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
        val thumbCenter = paddingLeft + progressRatio * netWidth
        val textX = thumbCenter - textBounds.width() / 2f
        val boxX = textX + textOffset
        textRect.set(
                boxX - boxBorder,
                -thumbHeight - textBounds.height() - boxBorder,
                boxX + textBounds.width() + boxBorder,
                -thumbHeight + boxBorder)
        canvas.drawRoundRect(textRect, rectCornerRadius, rectCornerRadius, textBackgroundPaint)
        canvas.drawText(thumbText, textX, -thumbHeight, textPaint)
    }
}
