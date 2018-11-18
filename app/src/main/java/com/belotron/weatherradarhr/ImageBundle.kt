package com.belotron.weatherradarhr

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.belotron.weatherradarhr.ImageBundle.Status.BROKEN
import com.belotron.weatherradarhr.ImageBundle.Status.HIDDEN
import com.belotron.weatherradarhr.ImageBundle.Status.LOADING
import com.belotron.weatherradarhr.ImageBundle.Status.SHOWING
import com.belotron.weatherradarhr.ImageBundle.Status.UNKNOWN

class ImageBundle {
    enum class Status {
        UNKNOWN, HIDDEN, LOADING, BROKEN, SHOWING
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
            viewGroup?.setVisible(value == SHOWING)
            brokenImgView?.setVisible(value == BROKEN)
            if (value != SHOWING) {
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
        destroyViews()
        status = HIDDEN
    }

    fun destroyViews() {
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
            mapShape: MapShape? = null,
            seekBar: ThumbSeekBar?,
            brokenImgView: ImageView,
            progressBar: ProgressBar
    ) {
        this.viewGroup = viewGroup
        this.textView = textView
        this.imgView = imgView
        mapShape?.also { imgView.mapShape = it }
        this.seekBar = seekBar
        seekBar?.progress = animationProgress
        this.brokenImgView = brokenImgView
        this.progressBar = progressBar
        this.status = this.status // reapplies the status to view visibility
    }
}
