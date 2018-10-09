package com.belotron.weatherradarhr

import android.content.Context
import android.content.Intent
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.graphics.PointF
import android.graphics.Rect
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
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.belotron.weatherradarhr.CcOption.CC_PRIVATE
import com.belotron.weatherradarhr.FetchPolicy.PREFER_CACHED
import com.belotron.weatherradarhr.FetchPolicy.UP_TO_DATE
import com.belotron.weatherradarhr.ImageBundle.Status.BROKEN
import com.belotron.weatherradarhr.ImageBundle.Status.HIDDEN
import com.belotron.weatherradarhr.ImageBundle.Status.LOADING
import com.belotron.weatherradarhr.ImageBundle.Status.SHOWING
import com.belotron.weatherradarhr.gifdecode.Allocator
import com.belotron.weatherradarhr.gifdecode.BitmapFreelists
import com.belotron.weatherradarhr.gifdecode.GifDecodeException
import com.belotron.weatherradarhr.gifdecode.GifDecoder
import com.belotron.weatherradarhr.gifdecode.GifFrame
import com.belotron.weatherradarhr.gifdecode.ParsedGif
import com.belotron.weatherradarhr.gifdecode.Pixels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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

class DisplayState : CoroutineScope {
    var indexOfImgInFullScreen: Int? = null
    val isInFullScreen: Boolean get() = indexOfImgInFullScreen != null
    val imgBundles: List<ImageBundle> = (0..1).map { ImageBundle() }

    override var coroutineContext = newCoroCtx()
        private set

    fun start(block: suspend CoroutineScope.() -> Unit) = this.launch(start = UNDISPATCHED, block = block)

    fun destroy() {
        imgBundles.forEach { it.destroyViews() }
        coroutineContext[Job]!!.cancel()
        coroutineContext = newCoroCtx()
    }

    private fun newCoroCtx() = Dispatchers.Main + Job()
}

class RadarImageFragment : Fragment() {

    val ds = DisplayState()
    private val fullScreenBundle = ImageBundle()
    private var stashedImgBundle = ImageBundle()
    private val animationLooper = AnimationLooper(ds)
    private var rootView: View? = null
    private var vGroupOverview: ViewGroup? = null
    private var vGroupFullScreen: ViewGroup? = null
    private var lastReloadedTimestamp = 0L
    private var wasFastResume = false
    // Serves to avoid IllegalStateException in DialogFragment.show()
    private var possibleStateLoss = false

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
                    coroScope = ds
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
                val textView = ds.imgBundles[1].textView ?: return true
                if (!textView.isDescendantOf(scrollView)) {
                    return true
                }
                scrollView.offsetDescendantRectToMyCoords(textView, rect.reset())
                val focusY = scrollView.scrollY + focusY
                val imgIndex = if (focusY <= rect.top) 0 else 1
                val imgView = ds
                        .imgBundles[imgIndex]
                        .takeIf { it.status == SHOWING }
                        ?.imgView
                        ?: return true
                if (!imgView.isDescendantOf(scrollView)) {
                    return true
                }
                scrollView.offsetDescendantRectToMyCoords(imgView, rect.reset())
                imgView.also {
                    enterFullScreen(imgIndex, it, focusX - rect.left, focusY - rect.top)
                }
                return true
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
        possibleStateLoss = false
        val activity = activity!!
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
            startFetchWidgetImages()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.recordSavingTime()
        possibleStateLoss = true
    }

    override fun onDestroyView() {
        info { "RadarImageFragment.onDestroyView" }
        super.onDestroyView()
        ds.destroy()
        fullScreenBundle.destroyViews()
        stashedImgBundle.destroyViews()
    }

    override fun onPause() {
        info { "RadarImageFragment.onPause" }
        super.onPause()
        wasFastResume = false
        animationLooper.stop()
        activity!!.sharedPrefs.applyUpdate {
            setLastReloadedTimestamp(lastReloadedTimestamp)
            setLastPausedTimestamp(System.currentTimeMillis())
        }
    }

    override fun onStop() {
        super.onStop()
        possibleStateLoss = true
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        info { "RadarImageFragment.onCreateOptionsMenu" }
        inflater.inflate(R.menu.main_menu, menu)
        menu.findItem(R.id.widget_log_enabled).isChecked = privateLogEnabled
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        info { "RadarImageFragment.onOptionsItemSelected" }
        val activity = activity as AppCompatActivity
        var shouldHideActionBar = true
        when (item.itemId) {
            R.id.refresh -> {
                startReloadAnimations(UP_TO_DATE)
                startFetchWidgetImages()
            }
            R.id.settings -> {
                startActivity(Intent(activity, SettingsActivity::class.java))
            }
            R.id.help -> startActivity(Intent(activity, HelpActivity::class.java))
            R.id.about -> ds.start {
                showAboutDialogFragment(activity)
            }
            R.id.rate_me -> activity.openAppRating()
            R.id.widget_log_enabled -> (!item.isChecked).also { newState ->
                if (!newState) info(CC_PRIVATE) { "Log disabled" }
                privateLogEnabled = newState
                if (newState) info(CC_PRIVATE) { "\n\nLog enabled" }
                item.isChecked = newState
                activity.sharedPrefs.applyUpdate { setWidgetLogEnabled(newState) }
            }
            R.id.show_widget_log -> startActivity(Intent(activity, ViewLogActivity::class.java))
            R.id.clear_widget_log -> clearPrivateLog()
            else -> shouldHideActionBar = false
        }
        if (shouldHideActionBar) activity.supportActionBar?.hide()
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
            ds.start {
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
        ds.start {
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
            if (!possibleStateLoss) {
                activity!!.maybeAskToRate()
            }
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

    private fun startReloadAnimations(fetchPolicy: FetchPolicy) {
        val context = activity as AppCompatActivity? ?: return
        animationLooper.stop()
        imgDescs.map { ds.imgBundles[it.index] }.forEach {
            it.status = LOADING
            it.animationProgress = 0
        }
        val rateMinsPerSec = context.sharedPrefs.rateMinsPerSec
        val freezeTimeMillis = context.sharedPrefs.freezeTimeMillis
        for (desc in imgDescs) {
            val bundle = ds.imgBundles[desc.index]
            ds.start {
                val coroScope = this
                try {
                    val (lastModified, parsedGif) = try {
                        fetchUrl(context, desc.url, fetchPolicy)
                    } catch (e: ImageFetchException) {
                        Pair(0L, e.cached)
                    }
                    if (parsedGif == null) {
                        bundle.status = BROKEN
                        return@start
                    }
                    lastReloadedTimestamp = System.currentTimeMillis()
                    parsedGif.apply {
                        assignTimestamps(coroScope, context, desc)
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
                    bundle.status = SHOWING
                    context.supportActionBar?.hide()
                } catch (t: Throwable) {
                    severe(t) { "Failed to load animated GIF ${desc.filename}" }
                    bundle.status = BROKEN
                }
            }
        }
    }

    private fun switchActionBarVisible() = run { activity?.switchActionBarVisible(); true }
}

private suspend fun ParsedGif.assignTimestamps(coroScope: CoroutineScope, context: Context, desc: ImgDescriptor) {
    val parsedGif = this
    BitmapFreelists().also { allocator ->
        (0 until frameCount).map { frameIndex ->
            coroScope.async(Dispatchers.Default) {
                context.decodeAndAssignTimestamp(parsedGif, frameIndex, desc, allocator)
            }
        }.forEachIndexed { i, it ->
            frames[i].timestamp = it.await()
        }
    }
}

private fun Context.decodeAndAssignTimestamp(
        parsedGif: ParsedGif, frameIndex: Int, desc: ImgDescriptor, allocator: Allocator
): Long {
    return try {
        GifDecoder(allocator, parsedGif).decodeFrame(frameIndex).let { decoder ->
            desc.ocrTimestamp(decoder.asPixels()).also {
                decoder.dispose()
            }
        }
    } catch (e: GifDecodeException) {
        severe { "Animated GIF decoding error" }
        invalidateCache(desc.url)
        throw e
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

private fun View.isDescendantOf(that: View): Boolean {
    if (this === that) {
        return true
    }
    var currParent: View? = parent as? View
    while (currParent != null) {
        if (currParent === that) {
            return true
        }
        currParent = currParent.parent as? View
    }
    return false
}
