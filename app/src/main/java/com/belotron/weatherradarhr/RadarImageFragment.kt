package com.belotron.weatherradarhr

import android.app.Fragment
import android.content.Intent
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.text.format.DateUtils.MINUTE_IN_MILLIS
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ScaleGestureDetector.SimpleOnScaleGestureListener
import android.view.View
import android.view.View.GONE
import android.view.View.INVISIBLE
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.belotron.weatherradarhr.FetchPolicy.PREFER_CACHED
import com.belotron.weatherradarhr.FetchPolicy.UP_TO_DATE
import com.belotron.weatherradarhr.ImageBundle.Status.BROKEN
import com.belotron.weatherradarhr.ImageBundle.Status.HIDDEN
import com.belotron.weatherradarhr.ImageBundle.Status.LOADING
import com.belotron.weatherradarhr.ImageBundle.Status.SHOWING
import com.belotron.weatherradarhr.ImageBundle.Status.UNKNOWN
import com.belotron.weatherradarhr.gifdecode.BitmapFreelists
import com.belotron.weatherradarhr.gifdecode.GifDecoder
import com.belotron.weatherradarhr.gifdecode.GifFrame
import com.belotron.weatherradarhr.gifdecode.GifParser
import com.belotron.weatherradarhr.gifdecode.ParsedGif
import com.belotron.weatherradarhr.gifdecode.Pixels
import com.google.android.gms.ads.AdView
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.async
import java.util.TreeSet


private const val ANIMATION_COVERS_MINUTES = 100
private const val A_WHILE_IN_MILLIS = 5 * MINUTE_IN_MILLIS

val imgDescs = arrayOf(
        ImgDescriptor(0, "HR", "http://vrijeme.hr/kradar-anim.gif", 15,
                R.id.vg_kradar, R.id.text_kradar, R.id.img_kradar, R.id.progress_bar_kradar, R.id.broken_img_kradar,
                KradarOcr::ocrKradarTimestamp),
        ImgDescriptor(1, "SLO", "http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar_anim.gif", 10,
                R.id.vg_lradar, R.id.text_lradar, R.id.img_lradar, R.id.progress_bar_lradar, R.id.broken_img_lradar,
                LradarOcr::ocrLradarTimestamp)
)

class ImgDescriptor(
        val index: Int,
        val title: String,
        val url: String,
        val minutesPerFrame: Int,
        val viewGroupId: Int,
        val textViewId: Int,
        val imgViewId: Int,
        val progressBarId: Int,
        val brokenImgViewId: Int,
        val ocrTimestamp: (Pixels) -> Long
) {
    val framesToKeep = Math.ceil(ANIMATION_COVERS_MINUTES.toDouble() / minutesPerFrame).toInt()
    val filename = url.substringAfterLast('/')
}

class ImageBundle {
    var textView: TextView? = null; private set
    var imgView: ImageView? = null; private set
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

    fun updateFrom(that: ImageBundle) {
        this.text = that.text
        this.bitmap = that.bitmap
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
            imgView: ImageView,
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

    enum class Status {
        UNKNOWN, HIDDEN, LOADING, BROKEN, SHOWING
    }
}

class DisplayState {
    var indexOfImgInFullScreen: Int? = null
    val isInFullScreen: Boolean get() = indexOfImgInFullScreen != null
    val imgBundles: List<ImageBundle> = (0..1).map { ImageBundle() }
}

class RadarImageFragment : Fragment() {

    val ds = DisplayState()
    private val fullScreenBundle = ImageBundle()
    private var stashedImgBundle = ImageBundle()
    private val animationLooper = AnimationLooper(ds)
    private var rootView: View? = null
    private val adView get() = rootView?.findViewById<AdView>(R.id.adView)
    private var vGroupOverview: ViewGroup? = null
    private var vGroupFullScreen: ViewGroup? = null
    private var lastReloadedTimestamp = 0L
    private var wasFastResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        info { "RadarImageFragment.onCreate" }
        super.onCreate(savedInstanceState)
        retainInstance = true
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        info { "RadarImageFragment.onCreateView" }
        wasFastResume = savedInstanceState?.savedStateRecently ?: false
        val rootView = inflater.inflate(R.layout.fragment_radar, container, false)
        this.rootView = rootView
        vGroupOverview = rootView.findViewById(R.id.radar_overview)
        vGroupFullScreen = rootView.findViewById(R.id.radar_zoomed)
        fullScreenBundle.restoreViews(
                viewGroup = rootView.findViewById(R.id.vg_radar_zoomed),
                textView = rootView.findViewById(R.id.text_radar_zoomed),
                imgView = rootView.findViewById<TouchImageView>(R.id.img_radar_zoomed).apply {
                    setOnDoubleTapListener(object: SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(e: MotionEvent) = switchActionBarVisible()
                        override fun onDoubleTap(e: MotionEvent) = run { exitFullScreen(); true }
                    })
                },
                seekBar = rootView.findViewById(R.id.radar_seekbar),
                brokenImgView = rootView.findViewById(R.id.broken_img_zoomed),
                progressBar = rootView.findViewById(R.id.progress_bar_zoomed)
        )
        with (fullScreenBundle.seekBar!!) {
            setOnSeekBarChangeListener(animationLooper)
            if (resources.configuration.orientation == ORIENTATION_LANDSCAPE) {
                with (layoutParams as FrameLayout.LayoutParams) {
                    gravity = Gravity.BOTTOM or Gravity.RIGHT
                    rightMargin = resources.getDimensionPixelOffset(R.dimen.seekbar_landscape_right_margin)
                }
            }
        }
        imgDescs.forEachIndexed { i, desc ->
            val viewGroup = rootView.findViewById<ViewGroup>(desc.viewGroupId)
            val imgView = rootView.findViewById<ImageView>(desc.imgViewId)
            val textView = rootView.findViewById<TextView>(desc.textViewId)
            val brokenImgView = rootView.findViewById<ImageView>(desc.brokenImgViewId)
            val progressBar = rootView.findViewById<ProgressBar>(desc.progressBarId)
            ds.imgBundles[i].restoreViews(
                viewGroup = viewGroup,
                textView = textView.apply {
                    GestureDetector(activity, MainViewListener(i, imgView)).also {
                        setOnTouchListener { _, e -> it.onTouchEvent(e); true }
                    }
                },
                imgView = imgView.apply {
                    GestureDetector(activity, MainViewListener(i, imgView)).also {
                        setOnTouchListener { _, e -> it.onTouchEvent(e); true }
                    }
                },
                seekBar = null,
                brokenImgView = brokenImgView.apply {
                    setOnClickListener { switchActionBarVisible() }
                    visibility = GONE
                },
                progressBar = progressBar.apply {
                    setOnClickListener { switchActionBarVisible() }
                }
        ) }
        val scrollView = rootView.findViewById<ScrollView>(R.id.radar_scrollview)
        val sl = object : SimpleOnScaleGestureListener() {
            private val rect = Rect()
            override fun onScale(detector: ScaleGestureDetector): Boolean = with (detector) {
                if (ds.isInFullScreen || scaleFactor <= 1) {
                    return true
                }
                scrollView.offsetDescendantRectToMyCoords(ds.imgBundles[1].textView ?: return true, rect.reset())
                val focusY = scrollView.scrollY + focusY
                val imgIndex = if (focusY <= rect.top) 0 else 1
                val imgView = ds.imgBundles[imgIndex].imgView ?: return true
                scrollView.offsetDescendantRectToMyCoords(imgView, rect.reset())
                imgView.also {
                    enterFullScreen(imgIndex, it, focusX - rect.left, focusY - rect.top)
                }
                true
            }
        }
        ScaleGestureDetector(activity, sl).also {
            var secondPointerDown = false
            scrollView.setOnTouchListener { _, e ->
                it.onTouchEvent(e)
                if (e.pointerCount > 1) {
                    secondPointerDown = true
                }
                try {
                    secondPointerDown
                } finally {
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        secondPointerDown = false
                    }
                }
            }
        }
        setupFullScreenBundle()
        updateFullScreenVisibility()
        updateAdVisibility()
        return rootView
    }

    private inner class MainViewListener(
            private val imgIndex: Int,
            private val imgView: ImageView
    ) : SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent) = switchActionBarVisible()
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!ds.isInFullScreen) enterFullScreen(imgIndex, imgView, e.x, e.y)
            return true
        }
    }

    override fun onResume() {
        val aWhileAgo = System.currentTimeMillis() - A_WHILE_IN_MILLIS
        info { "RadarImageFragment.onResume" }
        super.onResume()
        activity.sharedPrefs.also {
            lastReloadedTimestamp = it.lastReloadedTimestamp
            if (it.lastPausedTimestamp < aWhileAgo && ds.isInFullScreen) {
                exitFullScreen()
            }
        }
        val isTimeToReload = lastReloadedTimestamp < aWhileAgo
        val isAnimationShowing = ds.imgBundles.all { it.status == SHOWING || it.status == HIDDEN }
        if (isAnimationShowing && (wasFastResume || !isTimeToReload)) {
            with (activity.sharedPrefs) {
                animationLooper.resume(activity, rateMinsPerSec, freezeTimeMillis)
            }
        } else {
            info { "Reloading animations" }
            startReloadAnimations(if (isTimeToReload) UP_TO_DATE else PREFER_CACHED)
            activity.startFetchWidgetImages()
        }
        adView?.resume()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.recordSavingTime()
    }

    override fun onDestroyView() {
        info { "RadarImageFragment.onDestroyView" }
        super.onDestroyView()
        ds.imgBundles.forEach { it.destroyViews() }
        fullScreenBundle.destroyViews()
        stashedImgBundle.destroyViews()
        adView?.destroy()
    }

    override fun onPause() {
        info { "RadarImageFragment.onPause" }
        super.onPause()
        wasFastResume = false
        animationLooper.stop()
        activity.sharedPrefs.applyUpdate {
            setLastReloadedTimestamp(lastReloadedTimestamp)
            setLastPausedTimestamp(System.currentTimeMillis())
        }
        adView?.pause()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        info { "RadarImageFragment.onCreateOptionsMenu" }
        inflater.inflate(R.menu.main_menu, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        info { "RadarImageFragment.onOptionsItemSelected" }
        when (item.itemId) {
            R.id.refresh -> {
                startReloadAnimations(UP_TO_DATE)
                activity.startFetchWidgetImages()
            }
            R.id.settings -> {
                activity.actionBar.hide()
                startActivity(Intent(activity, SettingsActivity::class.java))
            }
            R.id.help -> startActivity(Intent(activity, HelpActivity::class.java))
            R.id.about -> start {
                showAboutDialogFragment(activity)
                updateAdVisibility()
                switchActionBarVisible()
            }
//            R.id.clear_rateme -> activity.clearRatemeState()
        }
        return true
    }

    private fun enterFullScreen(index: Int, srcImgView: ImageView, focusX: Float, focusY: Float) {
        val (bitmapW, bitmapH) = srcImgView.bitmapSize(PointF()) ?: return
        val focusInBitmapX = (focusX / srcImgView.width) * bitmapW
        val focusInBitmapY = (focusY / srcImgView.height) * bitmapH
        val (imgOnScreenX, imgOnScreenY) = IntArray(2).also { srcImgView.getLocationInWindow(it) }

        ds.indexOfImgInFullScreen = index
        with (fullScreenBundle) {
            imgView?.let { it as TouchImageView }?.reset()
            setupFullScreenBundle()
            updateFullScreenVisibility()
            animationLooper.resume(activity)
            seekBar?.visibility = INVISIBLE
            start {
                imgView?.let { it as TouchImageView }?.apply {
                    awaitBitmapMeasured()
                    animateZoomEnter(imgOnScreenX, imgOnScreenY, focusInBitmapX, focusInBitmapY)
                }
                seekBar?.startAnimateEnter()
            }
        }
    }

    fun exitFullScreen() {
        val index = ds.indexOfImgInFullScreen ?: return
        ds.indexOfImgInFullScreen = null
        start {
            val bundleInTransition = ds.imgBundles[index]
            if (bundleInTransition.status == SHOWING) {
                fullScreenBundle.seekBar?.startAnimateExit()
                bundleInTransition.imgView?.let { it as? TouchImageView }?.animateZoomExit()
                ds.imgBundles.forEach {
                    it.animationProgress = bundleInTransition.animationProgress
                }
                animationLooper.resume(activity)
            }
            stashedImgBundle.takeIf { it.imgView != null }?.apply {
                updateFrom(bundleInTransition)
                copyTo(bundleInTransition)
                clear()
            }
            fullScreenBundle.bitmap = null
            updateFullScreenVisibility()
            activity.maybeAskToRate()
        }
    }

    private fun updateFullScreenVisibility() {
        val makeFullScreenVisible = ds.isInFullScreen
        vGroupFullScreen?.setVisible(makeFullScreenVisible)
        vGroupOverview?.setVisible(!makeFullScreenVisible)
    }

    private fun setupFullScreenBundle() {
        val target = ds.imgBundles[ds.indexOfImgInFullScreen ?: return]
        target.copyTo(stashedImgBundle)
        with(fullScreenBundle) {
            updateFrom(target)
            copyTo(target)
        }
    }

    private fun updateAdVisibility() {
        val adView = adView ?: return
        val adsEnabled = activity.adsEnabled
        adView.setVisible(adsEnabled)
        if (adsEnabled) {
            adView.loadAd(adRequest())
        }
    }

    private fun startReloadAnimations(fetchPolicy: FetchPolicy) {
        val context = activity ?: return
        animationLooper.stop()
        imgDescs.map { ds.imgBundles[it.index] }.forEach {
            it.status = LOADING
            it.animationProgress = 0
        }
        val rateMinsPerSec = context.sharedPrefs.rateMinsPerSec
        val freezeTimeMillis = context.sharedPrefs.freezeTimeMillis
        for (desc in imgDescs) {
            val bundle = ds.imgBundles[desc.index]
            start {
                try {
                    val (lastModified, imgBytes) = try {
                        fetchUrl(context, desc.url, fetchPolicy)
                    } catch (e: ImageFetchException) {
                        Pair(0L, e.cached)
                    }
                    if (imgBytes == null) {
                        bundle.status = BROKEN
                        return@start
                    }
                    lastReloadedTimestamp = System.currentTimeMillis()
                    try {
                        val parsedGif = GifParser(imgBytes).parse().apply {
                            assignTimestamps(desc.ocrTimestamp)
                            sortAndDeduplicateFrames()
                            with (frames) {
                                while (size > desc.framesToKeep) {
                                    removeAt(0)
                                }
                            }
                        }
                        bundle.animationProgress = ds.imgBundles.map { it.animationProgress }.max() ?: 0
                        with (animationLooper) {
                            receiveNewGif(desc, parsedGif, isOffline = lastModified == 0L)
                            resume(context, rateMinsPerSec, freezeTimeMillis)
                        }
                    } catch (t: Throwable) {
                        error { "GIF parse error, deleting from cache" }
                        invalidateCache(context, desc.url)
                        throw t
                    }
                    bundle.status = SHOWING
                    context.actionBar.hide()
                } catch (t: Throwable) {
                    error(t) { "Failed to load animated GIF ${desc.filename}" }
                    bundle.status = BROKEN
                }
            }
        }
    }

    private fun switchActionBarVisible() = activity.switchActionBarVisible()
}

private suspend fun ParsedGif.assignTimestamps(ocrTimestamp: (Pixels) -> Long) {
    val parsedGif = this
    BitmapFreelists().also { freelists ->
        (0 until frameCount).map { i ->
            async(CommonPool) {
                GifDecoder(freelists, parsedGif).decodeFrame(i).let { decoder ->
                    ocrTimestamp(decoder.asPixels()).also {
                        decoder.dispose()
                    }
                }
            }
        }.forEachIndexed { i, it ->
            frames[i].timestamp = it.await()
        }
    }
}

private fun ParsedGif.sortAndDeduplicateFrames() {
    val sortedFrames = TreeSet<GifFrame>(compareBy(GifFrame::timestamp)).apply {
        addAll(frames)
    }
    frames.apply {
        clear()
        addAll(sortedFrames)
    }
}

private fun Rect.reset(): Rect {
    set(0, 0, 0, 0)
    return this
}
