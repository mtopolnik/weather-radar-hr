package com.belotron.weatherradarhr

import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.annotation.SuppressLint
import android.app.Activity
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
import androidx.core.content.PermissionChecker
import androidx.fragment.app.Fragment
import com.belotron.weatherradarhr.CcOption.CC_PRIVATE
import com.belotron.weatherradarhr.FetchPolicy.PREFER_CACHED
import com.belotron.weatherradarhr.FetchPolicy.UP_TO_DATE
import com.belotron.weatherradarhr.ImageBundle.Status.BROKEN
import com.belotron.weatherradarhr.ImageBundle.Status.LOADING
import com.belotron.weatherradarhr.ImageBundle.Status.SHOWING
import com.belotron.weatherradarhr.ImageBundle.Status.UNKNOWN
import com.belotron.weatherradarhr.gifdecode.ParsedGif
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.EnumSet
import kotlin.coroutines.CoroutineContext

private const val A_WHILE_IN_MILLIS = 5 * MINUTE_IN_MILLIS

class RadarImageFragment : Fragment(), CoroutineScope {

    val ds = DisplayState()
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

    private var _coroutineContext: CoroutineContext? = null
    override val coroutineContext get() = _coroutineContext!!

    override fun onCreate(savedInstanceState: Bundle?) {
        info { "RadarImageFragment.onCreate" }
        super.onCreate(savedInstanceState)
        ensureCoroutineContext()
        retainInstance = true
        setHasOptionsMenu(true)
        launch { checkAndCorrectPermissionsAndSettings() }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        info { "RadarImageFragment.onCreateView" }
        wasFastResume = savedInstanceState?.savedStateRecently ?: false
        val rootView = inflater.inflate(R.layout.fragment_radar, container, false)
        this.rootView = rootView
        vGroupOverview = rootView.findViewById(R.id.overview)
        vGroupFullScreen = rootView.findViewById(R.id.zoomed)
        fullScreenBundle.restoreViews(
                viewGroup = rootView.findViewById(R.id.vg_zoomed),
                textView = rootView.findViewById(R.id.text_zoomed),
                imgView = rootView.findViewById<TouchImageView>(R.id.img_zoomed).apply {
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
            val radarList = rootView.findViewById<ViewGroup>(R.id.radar_img_container)
            val radarGroup = inflater.inflate(R.layout.radar_frame, radarList, false)
            radarList.addView(radarGroup)
            val viewGroup = radarGroup.findViewById<ViewGroup>(R.id.radar_image_group)
            val imgView = radarGroup.findViewById<ImageViewWithLocation>(R.id.radar_img)
            val textView = radarGroup.findViewById<TextView>(R.id.radar_img_title_text)
            val brokenImgView = radarGroup.findViewById<ImageView>(R.id.radar_broken_img)
            val progressBar = radarGroup.findViewById<ProgressBar>(R.id.radar_progress_bar)
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
                    progressBar = progressBar
            )
        }
        (ds.imgBundles + fullScreenBundle).also { allBundles ->
            locationState.imageBundles = allBundles
            allBundles.map { it.imgView!! }.forEach {
                it.locationState = locationState
            }
        }
        val scrollView = rootView.findViewById<ScrollView>(R.id.scrollview)
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
                        .takeIf { it.status in ImageBundle.loadingOrShowing }
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
                secondPointerDown.also {
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
        ensureCoroutineContext()
        possibleStateLoss = false
        val activity = requireActivity()
        lastReloadedTimestamp = activity.mainPrefs.lastReloadedTimestamp
        val isTimeToReload = lastReloadedTimestamp < aWhileAgo
        val isAnimationShowing = ds.imgBundles.all { it.status !in EnumSet.of(UNKNOWN, BROKEN) }
        if (isAnimationShowing && (wasFastResume || !isTimeToReload)) {
            with (activity.mainPrefs) {
                animationLooper.resume(activity, rateMinsPerSec, freezeTimeMillis)
            }
        } else {
            val reloadJobs = startReloadAnimations(PREFER_CACHED)
            launch {
                reloadJobs.forEach { it.join() }
                (activity as AppCompatActivity).supportActionBar?.hide()
                if (isTimeToReload) {
                    info { "Reloading animations" }
                    startReloadAnimations(UP_TO_DATE)
                }
            }
            refreshWidgetsInForeground()
        }
        launch { locationState.trackLocationEnablement(activity) }
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
        with(requireActivity()) {
            stopReceivingAzimuthUpdates(locationState)
            stopReceivingLocationUpdatesFg()
            if (!anyWidgetInUse()) {
                stopReceivingLocationUpdatesBg()
            }
            mainPrefs.applyUpdate {
                setLastReloadedTimestamp(lastReloadedTimestamp)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        info { "RadarImageFragment.onStop" }
        cancelCoroutineContext()
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
                refreshWidgetsInForeground()
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
                activity.mainPrefs.applyUpdate { setWidgetLogEnabled(newState) }
            }
            R.id.show_widget_log -> startActivity(Intent(activity, ViewLogActivity::class.java))
            R.id.clear_widget_log -> clearPrivateLog()
            else -> shouldHideActionBar = false
        }
        if (shouldHideActionBar) activity.supportActionBar?.hide()
        return true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != CODE_REQUEST_FINE_LOCATION) return
        permissions.zip(grantResults.asList())
            .find { (perm, _) -> perm == ACCESS_FINE_LOCATION }
            ?.also { (_, result) ->
                if (result == PermissionChecker.PERMISSION_GRANTED) {
                    info { "User has granted us the fine location permission" }
                } else {
                    warn { "User hasn't granted us the fine location permission (grant result: ${grantResults[0]})" }
                }
            }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != CODE_RESOLVE_API_EXCEPTION) return
        if (resultCode == Activity.RESULT_OK) {
            info { "ResolvableApiException is now resolved" }
        } else {
            warn { "ResolvableApiException resolution failed with code $resultCode" }
            requireActivity().mainPrefs.applyUpdate { setShouldAskToEnableLocation(false) }
        }
    }

    private fun enterFullScreen(index: Int, srcImgView: ImageView, focusX: Float, focusY: Float) {
        val (bitmapW, bitmapH) = srcImgView.bitmapSize(PointF()) ?: return
        val focusInBitmapX = (focusX / srcImgView.width) * bitmapW
        val focusInBitmapY = (focusY / srcImgView.height) * bitmapH
        val (imgOnScreenX, imgOnScreenY) = IntArray(2).also { srcImgView.getLocationInWindow(it) }

        ds.indexOfImgInFullScreen = index
        with(fullScreenBundle) {
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
            if (bundleInTransition.status in ImageBundle.loadingOrShowing) {
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
                requireActivity().maybeAskToRate()
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

    private fun startReloadAnimations(fetchPolicy: FetchPolicy): List<Job> {
        lastReloadedTimestamp = System.currentTimeMillis()
        val context = appContext
        imgDescs.map { ds.imgBundles[it.index] }.forEach { it.status = LOADING }
        val rateMinsPerSec = context.mainPrefs.rateMinsPerSec
        val freezeTimeMillis = context.mainPrefs.freezeTimeMillis
        return imgDescs.map { desc ->
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
                    parsedGif.apply {
                        assignTimestamps(coroScope, context, desc)
                        sortAndDeduplicateFrames()
                        with(frames) {
                            while (size > desc.framesToKeep) {
                                removeAt(0)
                            }
                        }
                    }
                    bundle.animationProgress = ds.imgBundles.map { it.animationProgress }.max() ?: 0
                    with(animationLooper) {
                        receiveNewGif(desc, parsedGif, isOffline = lastModified == 0L)
                        resume(context, rateMinsPerSec, freezeTimeMillis)
                    }
                    bundle.status = SHOWING
                } catch (t: Throwable) {
                    severe(t) { "Failed to load animated GIF ${desc.filename}" }
                    bundle.status = BROKEN
                }
            }
        }
    }

    private fun ensureCoroutineContext() {
        _coroutineContext ?: run { _coroutineContext = SupervisorJob() + Dispatchers.Main }
    }

    private fun cancelCoroutineContext() {
        _coroutineContext?.also {
            it[Job]!!.cancel()
            _coroutineContext = null
        }
    }

    private fun switchActionBarVisible() = run { activity?.switchActionBarVisible(); true }
}
