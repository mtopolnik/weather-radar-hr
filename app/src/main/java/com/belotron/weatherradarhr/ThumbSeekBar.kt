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
import android.view.View
import android.widget.SeekBar


class ThumbSeekBar(context : Context, attrs: AttributeSet) : SeekBar(context, attrs) {
    var thumbText: String = ""
    var thumbProgress: Int = 0

    // anchor position of the seekbar within its parent, needed to restore the
    // position after enter/exit animation
    private var anchorY = 0f

    private val thumbHeight = resources.getDimensionPixelOffset(R.dimen.seekbar_thumb_height).toFloat()
    private val boxBorder = resources.getDimensionPixelOffset(R.dimen.seekbar_thumb_box_border).toFloat()
    private val triangleHalfWidth = resources.getDimensionPixelOffset(R.dimen.seekbar_thumb_triangle_half_width).toFloat()
    private val textOffset = resources.getDimensionPixelOffset(R.dimen.seekbar_thumb_text_offset).toFloat()
    private val rectCornerRadius = resources.getDimensionPixelOffset(R.dimen.seekbar_thumb_box_corner_radius).toFloat()
    private val textBounds = Rect()
    private val textRect = RectF()
    private val trianglePath = Path()
    private val textPaint = TextPaint().apply {
        color = resources.getColor(R.color.seekbarThumbText)
        textSize = resources.getDimensionPixelSize(R.dimen.seekbar_thumb_text_size).toFloat()
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
        trianglePath.apply {
            reset()
            moveTo(thumbCenter - triangleHalfWidth, -thumbHeight)
            lineTo(thumbCenter, 0f)
            lineTo(thumbCenter + triangleHalfWidth, -thumbHeight)
            close()
        }
        textRect.set(
                textX - boxBorder,
                -thumbHeight - textBounds.height() - 2 * boxBorder,
                textX + textBounds.width() + boxBorder,
                -thumbHeight)
        canvas.drawRoundRect(textRect, rectCornerRadius, rectCornerRadius, textBackgroundPaint)
        canvas.drawPath(trianglePath, textBackgroundPaint)
        canvas.drawText(thumbText, textX - textOffset, -thumbHeight - boxBorder, textPaint)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        anchorY = top.toFloat()
    }

    fun startAnimateEnter() {
        y = parentHeight
        visibility = VISIBLE
        animate().y(anchorY).duration = 200
    }

    fun startAnimateExit() {
        animate().y(parentHeight).withEndAction {
            visibility = View.INVISIBLE
        }
    }

    private val parentHeight get() = (parent as View).height.toFloat()
}
