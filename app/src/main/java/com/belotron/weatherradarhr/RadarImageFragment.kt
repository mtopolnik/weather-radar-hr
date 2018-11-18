package com.belotron.weatherradarhr

import android.content.Intent
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.text.format.DateUtils
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
import com.belotron.weatherradarhr.gifdecode.ParsedGif
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices.getFusedLocationProviderClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val A_WHILE_IN_MILLIS = 5 * DateUtils.MINUTE_IN_MILLIS

class RadarImageFragment : Fragment(), CoroutineScope {

    val ds = DisplayState()
    private lateinit var locationClient: FusedLocationProviderClient
    private val locationState = LocationState()
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

    override val coroutineContext = SupervisorJob() + Dispatchers.Main

    override fun onCreate(savedInstanceState: Bundle?) {
        info { "RadarImageFragment.onCreate" }
        super.onCreate(savedInstanceState)
        retainInstance = true
        setHasOptionsMenu(true)
        locationClient = getFusedLocationProviderClient(context!!)
    }

    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        info { "RadarImageFragment.onCreateView" }
        wasFastResume = savedInstanceState?.savedStateRecently ?: false
        ds.startCoroutineScope()
        val rootView = inflater.inflate(R.layout.fragment_radar, container, false)
        this.rootView = rootView
        vGroupOverview = rootView.findViewById(R.id.radar_overview)
        vGroupFullScreen = rootView.findViewById(R.id.radar_zoomed)
        fullScreenBundle.restoreViews(
                viewGroup = rootView.findViewById(R.id.vg_radar_zoomed),
                textView = rootView.findViewById(R.id.text_radar_zoomed),
                imgView = rootView.findViewById<TouchImageView>(R.id.img_radar_zoomed).apply {
                    coroScope = ds
                    setOnDoubleTapListener(object : SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(e: MotionEvent) = switchActionBarVisible()
                        override fun onDoubleTap(e: MotionEvent) = run { exitFullScreen(); true }
                    })
                },
                seekBar = rootView.findViewById(R.id.radar_seekbar),
                brokenImgView = rootView.findViewById(R.id.broken_img_zoomed),
                progressBar = rootView.findViewById(R.id.progress_bar_zoomed)
        )
        with(fullScreenBundle.seekBar!!) {
            setOnSeekBarChangeListener(animationLooper)
            if (resources.configuration.orientation == ORIENTATION_LANDSCAPE) {
                with(layoutParams as FrameLayout.LayoutParams) {
                    gravity = Gravity.BOTTOM or Gravity.RIGHT
                    rightMargin = resources.getDimensionPixelOffset(R.dimen.seekbar_landscape_right_margin)
                }
            }
        }
        imgDescs.forEachIndexed { i, desc ->
            val viewGroup = rootView.findViewById<ViewGroup>(desc.viewGroupId)
            val imgView = rootView.findViewById<ImageViewWithLocation>(desc.imgViewId)
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
                    mapShape = desc.mapShape,
                    seekBar = null,
                    brokenImgView = brokenImgView.apply {
                        setOnClickListener { switchActionBarVisible() }
                        visibility = GONE
                    },
                    progressBar = progressBar.apply {
                        setOnClickListener { switchActionBarVisible() }
                    }
            )
        }
        (ds.imgBundles + fullScreenBundle).also { allBundles ->
            locationState.imageBundles = allBundles
            allBundles.map { it.imgView!! }.forEach { it.locationState = locationState }
        }
        receiveAzimuthUpdates(
                azimuthChanged = { azimuth ->
                    locationState.azimuth = azimuth
                },
                accuracyChanged = { accuracy ->
                    locationState.azimuthAccuracy = accuracy
                })
        launch {
            receiveLocationUpdates(locationClient) { location ->
                info { "Our lat: ${location.latitude} lon: ${location.longitude} accuracy: ${location.accuracy}" +
                        " bearing: ${location.bearing}" }
                locationState.location = location
            }
        }
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
        coroutineContext[Job]!!.cancel()
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        require(permissions.size == 1)
        resumeReceiveLocationUpdates(requestCode, grantResults[0])
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        resumeReceiveLocationUpdates(requestCode, resultCode)
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
                        context.fetchGif(desc.url, fetchPolicy)
                    } catch (e: ImageFetchException) {
                        Pair(0L, e.cached as ParsedGif?)
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
