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
import android.graphics.drawable.BitmapDrawable
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.belotron.weatherradarhr.ImageBundle.Status.*
import java.util.*

class ImageBundle {
    enum class Status {
        UNKNOWN, HIDDEN, LOADING, BROKEN, SHOWING
    }
    companion object {
        val loadingOrShowing = EnumSet.of(LOADING, SHOWING)!!
    }

    var textView: TextView? = null; private set
    var imgView: ImageViewWithLocation? = null; private set
    var seekBar: ThumbSeekBar? = null; private set
    private var viewGroup: ViewGroup? = null
    private var brokenImgView: ImageView? = null
    private var progressBar: ProgressBar? = null

    var animationProgress: Int = 0

    var status = UNKNOWN
        set(value) {
            field = value
            progressBar?.setVisible(value == LOADING)
            viewGroup?.setVisible(value in loadingOrShowing)
            brokenImgView?.setVisible(value == BROKEN)
            if (value !in loadingOrShowing) {
                textView?.text = ""
            }
        }

    var text: CharSequence
        get() = textView!!.text
        set(value) { textView!!.text = value }

    var bitmap: Bitmap?
        get() = imgView!!.drawable?.let { it as BitmapDrawable }?.bitmap
        set(value) { imgView!!.setImageBitmap(value) }

    fun invalidateImgView() = imgView?.invalidate()

    fun updateFrom(that: ImageBundle) {
        this.text = that.text
        this.bitmap = that.bitmap
        this.imgView!!.mapShape = that.imgView!!.mapShape
        this.status = that.status
        this.animationProgress = that.animationProgress
    }

    fun copyTo(that: ImageBundle) {
        that.viewGroup = this.viewGroup!!
        that.textView = this.textView!!
        that.imgView = this.imgView!!
        that.seekBar = this.seekBar
        that.brokenImgView = this.brokenImgView!!
        that.progressBar = this.progressBar!!
        that.status = this.status
        that.animationProgress = this.animationProgress
    }

    fun clear() {
        removeViews()
        status = HIDDEN
    }

    fun removeViews() {
        this.viewGroup = null
        this.textView = null
        this.imgView = null
        this.seekBar = null
        this.brokenImgView = null
        this.progressBar = null
    }

    fun restoreViews(
            viewGroup: ViewGroup,
            textView: TextView,
            imgView: ImageViewWithLocation,
            seekBar: ThumbSeekBar?,
            brokenImgView: ImageView,
            progressBar: ProgressBar
    ) {
        this.viewGroup = viewGroup
        this.textView = textView
        this.imgView = imgView
        this.seekBar = seekBar
        seekBar?.progress = animationProgress
        this.brokenImgView = brokenImgView
        this.progressBar = progressBar
        this.status = this.status // reapplies the status to view visibility
    }
}
