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
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.launch


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
        val ocrTimestamp: (Bitmap) -> Long
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

class RadarImageFragment : Fragment() {
    var isInFullScreen: Boolean = false

    private val imgBundles: List<ImageBundle> = (0..1).map { ImageBundle() }
    private val fullScreenBundle = ImageBundle()
    private var stashedImgBundle = ImageBundle()
    private val animationLooper = AnimationLooper(imgBundles)
    private var rootView: View? = null
    private val adView get() = rootView?.findViewById<AdView>(R.id.adView)
    private var vGroupOverview: ViewGroup? = null
    private var vGroupFullScreen: ViewGroup? = null
    private var indexOfImgInFullScreen: Int? = null
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
        imgDescs.forEachIndexed { i, desc -> imgBundles[i].restoreViews(
                viewGroup = rootView.findViewById(desc.viewGroupId),
                textView = rootView.findViewById(desc.textViewId),
                imgView = rootView.findViewById<ImageView>(desc.imgViewId).also { imgView ->
                    val gl = object : SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(e: MotionEvent) = switchActionBarVisible()
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (!isInFullScreen) enterFullScreen(i, imgView, e.x, e.y)
                            return true
                        }
                    }
                    GestureDetector(activity, gl).also {
                        imgView.setOnTouchListener { _, e -> it.onTouchEvent(e); true }
                    }
                },
                seekBar = null,
                brokenImgView = rootView.findViewById<ImageView>(desc.brokenImgViewId).apply {
                    setOnClickListener { switchActionBarVisible() }
                    visibility = GONE
                },
                progressBar = rootView.findViewById<ProgressBar>(desc.progressBarId).apply {
                    setOnClickListener { switchActionBarVisible() }
                }
        ) }
        val scrollView = rootView.findViewById<ScrollView>(R.id.radar_scrollview)
        val sl = object : SimpleOnScaleGestureListener() {
            private val rect = Rect()
            override fun onScale(detector: ScaleGestureDetector): Boolean = with (detector) {
                if (isInFullScreen || scaleFactor <= 1) {
                    return true
                }
                scrollView.offsetDescendantRectToMyCoords(imgBundles[1].textView ?: return true, rect.reset())
                val imgIndex = if (focusY <= rect.top) 0 else 1
                val imgView = imgBundles[imgIndex].imgView ?: return true
                scrollView.offsetDescendantRectToMyCoords(imgView, rect.reset())
                imgView.also {
                    enterFullScreen(imgIndex, it, focusX - rect.left, focusY - rect.top)
                }
                true
            }
        }
        ScaleGestureDetector(activity, sl).also {
            scrollView.setOnTouchListener { _, e -> it.onTouchEvent(e); false }
        }
        setupFullScreenBundle()
        updateFullScreenVisibility()
        updateAdVisibility()
        return rootView
    }

    override fun onResume() {
        val aWhileAgo = System.currentTimeMillis() - A_WHILE_IN_MILLIS
        info { "RadarImageFragment.onResume" }
        super.onResume()
        activity.sharedPrefs.also {
            lastReloadedTimestamp = it.lastReloadedTimestamp
            if (it.lastPausedTimestamp < aWhileAgo && isInFullScreen) {
                exitFullScreen()
            }
        }
        val isTimeToReload = lastReloadedTimestamp < aWhileAgo
        val isAnimationShowing = imgBundles.all { it.status == SHOWING || it.status == HIDDEN }
        if (isAnimationShowing && (wasFastResume || !isTimeToReload)) {
            with (activity.sharedPrefs) {
                animationLooper.resume(rateMinsPerSec, freezeTimeMillis)
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
        imgBundles.forEach { it.destroyViews() }
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
            R.id.settings -> startActivity(Intent(activity, SettingsActivity::class.java))
            R.id.help -> startActivity(Intent(activity, HelpActivity::class.java))
            R.id.about -> start {
                showAboutDialogFragment(activity)
                updateAdVisibility()
                switchActionBarVisible()
            }
        }
        return true
    }

    private fun enterFullScreen(index: Int, srcImgView: ImageView, focusX: Float, focusY: Float) {
        val (bitmapW, bitmapH) = srcImgView.bitmapSize(PointF()) ?: return
        val focusInBitmapX = (focusX / srcImgView.width) * bitmapW
        val focusInBitmapY = (focusY / srcImgView.height) * bitmapH
        val (imgOnScreenX, imgOnScreenY) = IntArray(2).also { srcImgView.getLocationInWindow(it) }

        indexOfImgInFullScreen = index
        with (fullScreenBundle) {
            imgView?.let { it as TouchImageView }?.reset()
            setupFullScreenBundle()
            updateFullScreenVisibility()
            seekBar?.visibility = INVISIBLE
            start {
                imgView?.let { it as TouchImageView }?.apply {
                    awaitOnDraw()
                    animateZoomEnter(imgOnScreenX, imgOnScreenY, focusInBitmapX, focusInBitmapY)
                }
                seekBar?.apply {
                    setVisible(true)
                    animateEnter()
                }
            }
        }
    }

    fun exitFullScreen() {
        val index = indexOfImgInFullScreen ?: return
        indexOfImgInFullScreen = null
        start {
            val target = imgBundles[index]
            if (target.status == SHOWING) {
                fullScreenBundle.seekBar?.animateExit()
                target.imgView?.let { it as? TouchImageView }?.animateZoomExit()
            }
            stashedImgBundle.takeIf { it.imgView != null }?.apply {
                updateFrom(target)
                copyTo(target)
                clear()
            }
            fullScreenBundle.bitmap = null
            updateFullScreenVisibility()
        }
    }

    private fun updateFullScreenVisibility() {
        val index = indexOfImgInFullScreen
        val makeFullScreenVisible = index != null
        isInFullScreen = makeFullScreenVisible
        vGroupFullScreen?.setVisible(makeFullScreenVisible)
        vGroupOverview?.setVisible(!makeFullScreenVisible)
    }

    private fun setupFullScreenBundle() {
        val target = imgBundles[indexOfImgInFullScreen ?: return]
        target.copyTo(stashedImgBundle)
        with(fullScreenBundle) {
            updateFrom(target)
            copyTo(target)
        }
    }

    private fun updateAdVisibility() {
        val adView = adView ?: return
        val adsEnabled = activity.sharedPrefs.adsEnabled
        adView.setVisible(adsEnabled)
        if (adsEnabled) {
            adView.loadAd(AdRequest.Builder().build())
        }
    }

    private fun startReloadAnimations(fetchPolicy: FetchPolicy) {
        val context = activity ?: return
        animationLooper.stop()
        imgDescs.map { imgBundles[it.index] }.forEach {
            it.status = LOADING
            it.animationProgress = 0
        }
        val rateMinsPerSec = context.sharedPrefs.rateMinsPerSec
        val freezeTimeMillis = context.sharedPrefs.freezeTimeMillis
        for (desc in imgDescs) {
            val bundle = imgBundles[desc.index]
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
                    val gifData = editGif(imgBytes, desc.framesToKeep)
                    bundle.animationProgress = imgBundles.map { it.animationProgress }.max() ?: 0
                    with (animationLooper) {
                        receiveNewGif(desc, gifData, isOffline = lastModified == 0L)
                        resume(rateMinsPerSec, freezeTimeMillis)
                    }
                    bundle.status = SHOWING
                    context.actionBar.hide()
                } catch (t: Throwable) {
                    error(t) {"Failed to load animated GIF ${desc.filename}"}
                    bundle.status = BROKEN
                }
            }
        }
    }

    private fun switchActionBarVisible() = activity.switchActionBarVisible()
}

private fun Rect.reset(): Rect {
    set(0, 0, 0, 0)
    return this
}
