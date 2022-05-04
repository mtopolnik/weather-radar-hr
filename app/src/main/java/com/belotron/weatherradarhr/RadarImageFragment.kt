package com.belotron.weatherradarhr

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.graphics.PointF
import android.graphics.Rect
import android.net.Uri
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
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.belotron.weatherradarhr.CcOption.CC_PRIVATE
import com.belotron.weatherradarhr.FetchPolicy.PREFER_CACHED
import com.belotron.weatherradarhr.FetchPolicy.UP_TO_DATE
import com.belotron.weatherradarhr.ImageBundle.Status.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.*
import kotlin.math.min
import kotlin.math.roundToInt

private const val A_WHILE_IN_MILLIS = 5 * MINUTE_IN_MILLIS

class RadarImageViewModel : ViewModel() {
    var indexOfImgInFullScreen: Int? = null
        set(value) {
            field = value
            if (value == null) {
                isTrackingTouch = false
            }
        }

    var isTrackingTouch = false
    val isInFullScreen: Boolean get() = indexOfImgInFullScreen != null
    val imgBundles: List<ImageBundle> = (0..1).map { ImageBundle() }
    val locationState = LocationState()
    val fullScreenBundle = ImageBundle()
    var stashedImgBundle = ImageBundle()
    val animationLooper = AnimationLooper(this)
    var reloadJob: Job? = null
    var lastReloadedTimestamp = 0L
    // Serves to avoid IllegalStateException in DialogFragment.show()
    var possibleStateLoss = false

    fun destroyImgBundles() {
        imgBundles.forEach { it.destroyViews() }
        fullScreenBundle.destroyViews()
        stashedImgBundle.destroyViews()
    }
}

class RadarImageFragment : Fragment() {

    val permissionRequest = registerForActivityResult(RequestPermission()) {
        if (it) info { "User has granted us the location permission" }
        else warn { "User hasn't granted us the location permission" }
    }
    val resolveApiExceptionRequest = registerForActivityResult(StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            info { "ResolvableApiException is now resolved" }
        } else {
            warn { "ResolvableApiException resolution failed with code ${it.resultCode}" }
            requireActivity().mainPrefs.applyUpdate { setShouldAskToEnableLocation(false) }
        }
    }

    lateinit var vmodel: RadarImageViewModel
    private var wasFastResume = false
    private var vGroupOverview: ViewGroup? = null
    private var vGroupFullScreen: ViewGroup? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        info { "RadarImageFragment.onCreate" }
        super.onCreate(savedInstanceState)
        vmodel = ViewModelProvider(this).get(RadarImageViewModel::class.java)
        setHasOptionsMenu(true)
        lifecycleScope.launch { checkAndCorrectPermissionsAndSettings() }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        info { "RadarImageFragment.onCreateView" }
        wasFastResume = savedInstanceState?.savedStateRecently ?: false
        val rootView = inflater.inflate(R.layout.fragment_radar, container, false)
        vGroupOverview = rootView.findViewById<ViewGroup>(R.id.overview).also {
            it.doOnLayout { view ->
                val w = view.measuredWidth
                val h = view.measuredHeight
                val adjustedWidth = min(w, (1.25 * h).roundToInt())
                view.layoutParams.width = adjustedWidth
                view.requestLayout()
            }
        }
        vGroupFullScreen = rootView.findViewById(R.id.zoomed)
        vmodel.fullScreenBundle.restoreViews(
                viewGroup = rootView.findViewById(R.id.vg_zoomed),
                textView = rootView.findViewById(R.id.text_zoomed),
                imgView = rootView.findViewById<TouchImageView>(R.id.img_zoomed).apply {
                    coroScope = vmodel.viewModelScope
                    setOnDoubleTapListener(object : SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(e: MotionEvent) = switchActionBarVisible()
                        override fun onDoubleTap(e: MotionEvent) = run { exitFullScreen(); true }
                    })
                },
                seekBar = rootView.findViewById(R.id.radar_seekbar),
                brokenImgView = rootView.findViewById(R.id.broken_img_zoomed),
                progressBar = rootView.findViewById(R.id.progress_bar_zoomed)
        )
        with(vmodel.fullScreenBundle.seekBar!!) {
            setOnSeekBarChangeListener(vmodel.animationLooper)
            if (resources.configuration.orientation == ORIENTATION_LANDSCAPE) {
                with(layoutParams as FrameLayout.LayoutParams) {
                    gravity = Gravity.BOTTOM or Gravity.END
                    rightMargin = resources.getDimensionPixelOffset(R.dimen.seekbar_landscape_right_margin)
                }
            }
        }
        frameSequenceLoaders.forEachIndexed { i, loader ->
            val radarList = rootView.findViewById<ViewGroup>(R.id.radar_img_container)
            val radarGroup = inflater.inflate(R.layout.radar_frame, radarList, false)
            radarList.addView(radarGroup)
            val viewGroup = radarGroup.findViewById<ViewGroup>(R.id.radar_image_group)
            val imgView = radarGroup.findViewById<ImageViewWithLocation>(R.id.radar_img)
            val textView = radarGroup.findViewById<TextView>(R.id.radar_img_title_text)
            val brokenImgView = radarGroup.findViewById<ImageView>(R.id.radar_broken_img)
            val progressBar = radarGroup.findViewById<ProgressBar>(R.id.radar_progress_bar)
            vmodel.imgBundles[i].restoreViews(
                    viewGroup = viewGroup,
                    textView = textView.apply {
                        GestureDetector(activity, MainViewListener(i, imgView)).also { gd ->
                            setOnTouchListener { _, e -> gd.onTouchEvent(e); true }
                        }
                    },
                    imgView = imgView.apply {
                        GestureDetector(activity, MainViewListener(i, imgView)).also { gd ->
                            setOnTouchListener { _, e -> gd.onTouchEvent(e); true }
                        }
                    },
                    mapShape = loader.mapShape,
                    seekBar = null,
                    brokenImgView = brokenImgView.apply {
                        setOnClickListener { switchActionBarVisible() }
                        visibility = GONE
                    },
                    progressBar = progressBar
            )
        }
        (vmodel.imgBundles + vmodel.fullScreenBundle).also { allBundles ->
            vmodel.locationState.imageBundles = allBundles
            allBundles.map { it.imgView!! }.forEach {
                it.locationState = vmodel.locationState
            }
        }
        val scrollView = rootView.findViewById<ScrollView>(R.id.scrollview)
        val sl = object : SimpleOnScaleGestureListener() {
            private val rect = Rect()
            override fun onScale(detector: ScaleGestureDetector): Boolean = with (detector) {
                if (vmodel.isInFullScreen || scaleFactor <= 1) {
                    return true
                }
                val textView = vmodel.imgBundles[1].textView ?: return true
                if (!textView.isDescendantOf(scrollView)) {
                    return true
                }
                scrollView.offsetDescendantRectToMyCoords(textView, rect.reset())
                val focusY = scrollView.scrollY + focusY
                val imgIndex = if (focusY <= rect.top) 0 else 1
                val imgView = vmodel
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
            if (!vmodel.isInFullScreen) enterFullScreen(imgIndex, imgView, e.x, e.y)
            return true
        }
    }

    override fun onResume() {
        info { "RadarImageFragment.onResume" }
        val aWhileAgo = System.currentTimeMillis() - A_WHILE_IN_MILLIS
        super.onResume()
        vmodel.possibleStateLoss = false
        val activity = requireActivity()
        vmodel.lastReloadedTimestamp = activity.mainPrefs.lastReloadedTimestamp
        val isTimeToReload = vmodel.lastReloadedTimestamp < aWhileAgo
        val isAnimationShowing = vmodel.imgBundles.all { it.status !in EnumSet.of(UNKNOWN, BROKEN) }
        if (isAnimationShowing && (wasFastResume || !isTimeToReload)) {
            with (activity.mainPrefs) {
                vmodel.animationLooper.resume(activity,
                        newAnimationCoversMinutes = animationCoversMinutes,
                        newRateMinsPerSec = rateMinsPerSec,
                        newFreezeTimeMillis = freezeTimeMillis)
            }
        } else {
            startReloadAnimations(PREFER_CACHED)
            lifecycleScope.launch {
                vmodel.reloadJob?.join()
                (activity as AppCompatActivity).supportActionBar?.hide()
                if (isTimeToReload) {
                    info { "Reloading animations" }
                    startReloadAnimations(UP_TO_DATE)
                }
            }
            refreshWidgetsInForeground()
        }
        lifecycleScope.launch { vmodel.locationState.trackLocationEnablement(activity) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        info { "RadarImageFragment.onSaveInstanceState" }
        outState.recordSavingTime()
        vmodel.possibleStateLoss = true
    }

    override fun onDestroyView() {
        info { "RadarImageFragment.onDestroyView" }
        super.onDestroyView()
        vmodel.destroyImgBundles()
    }

    override fun onPause() {
        info { "RadarImageFragment.onPause" }
        super.onPause()
        wasFastResume = false
        vmodel.animationLooper.stop()
        with(requireActivity()) {
            stopReceivingAzimuthUpdates(vmodel.locationState)
            stopReceivingLocationUpdatesFg()
            if (!anyWidgetInUse()) {
                stopReceivingLocationUpdatesBg()
            }
            mainPrefs.applyUpdate {
                setLastReloadedTimestamp(vmodel.lastReloadedTimestamp)
            }
        }
    }

    override fun onStop() {
        info { "RadarImageFragment.onStop" }
        super.onStop()
        vmodel.possibleStateLoss = true
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
            R.id.about -> vmodel.viewModelScope.launch {
                showAboutDialogFragment(activity)
            }
            R.id.privacy_policy -> startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://morestina.net/vnr-privacy-policy.html")
            ))
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

    private fun enterFullScreen(index: Int, srcImgView: ImageView, focusX: Float, focusY: Float) {
        val (bitmapW, bitmapH) = srcImgView.bitmapSize(PointF()) ?: return
        val viewWidth = srcImgView.width
        val focusInBitmapX = (focusX / viewWidth) * bitmapW
        val focusInBitmapY = (focusY / srcImgView.height) * bitmapH
        val (imgOnScreenX, imgOnScreenY) = IntArray(2).also { srcImgView.getLocationInWindow(it) }

        vmodel.indexOfImgInFullScreen = index
        with(vmodel.fullScreenBundle) {
            imgView?.let { it as TouchImageView }?.reset()
            setupFullScreenBundle()
            updateFullScreenVisibility()
            vmodel.animationLooper.resume(activity)
            seekBar?.visibility = INVISIBLE
            lifecycleScope.launch {
                imgView?.let { it as TouchImageView }?.apply {
                    awaitBitmapMeasured()
                    animateZoomEnter(imgOnScreenX, imgOnScreenY, viewWidth, focusInBitmapX, focusInBitmapY)
                }
                seekBar?.startAnimateEnter()
            }
        }
    }

    fun exitFullScreen() {
        val index = vmodel.indexOfImgInFullScreen ?: return
        vmodel.indexOfImgInFullScreen = null
        lifecycleScope.launch {
            val bundleInTransition = vmodel.imgBundles[index]
            if (bundleInTransition.status in ImageBundle.loadingOrShowing) {
                vmodel.fullScreenBundle.seekBar?.startAnimateExit()
                bundleInTransition.imgView?.let { it as? TouchImageView }?.animateZoomExit()
                vmodel.imgBundles.forEach {
                    it.animationProgress = bundleInTransition.animationProgress
                }
                vmodel.animationLooper.resume(activity)
            }
            vmodel.stashedImgBundle.takeIf { it.imgView != null }?.apply {
                updateFrom(bundleInTransition)
                copyTo(bundleInTransition)
                clear()
            }
            vmodel.fullScreenBundle.bitmap = null
            updateFullScreenVisibility()
            if (!vmodel.possibleStateLoss) {
                requireActivity().maybeAskToRate()
            }
        }
    }

    private fun updateFullScreenVisibility() {
        val makeFullScreenVisible = vmodel.isInFullScreen
        vGroupFullScreen?.setVisible(makeFullScreenVisible)
        vGroupOverview?.setVisible(!makeFullScreenVisible)
    }

    private fun setupFullScreenBundle() {
        val target = vmodel.imgBundles[vmodel.indexOfImgInFullScreen ?: return]
        target.copyTo(vmodel.stashedImgBundle)
        with(vmodel.fullScreenBundle) {
            updateFrom(target)
            copyTo(target)
        }
    }

    private fun startReloadAnimations(fetchPolicy: FetchPolicy) {
        vmodel.lastReloadedTimestamp = System.currentTimeMillis()
        val context = appContext
        frameSequenceLoaders.map { vmodel.imgBundles[it.positionInUI] }.forEach { it.status = LOADING }
        val rateMinsPerSec = context.mainPrefs.rateMinsPerSec
        val freezeTimeMillis = context.mainPrefs.freezeTimeMillis
        vmodel.reloadJob?.cancel()
        vmodel.reloadJob = lifecycleScope.launch {
            supervisorScope {
                for (loader in frameSequenceLoaders) {
                    val bundle = vmodel.imgBundles[loader.positionInUI]
                    launch reloadOne@ {
                        try {
                            val animationCoversMinutes = context.mainPrefs.animationCoversMinutes
                            val (isOffline, frameSequence) = loader.fetchFrameSequence(
                                    context, animationCoversMinutes, fetchPolicy)
                            if (frameSequence == null) {
                                bundle.status = BROKEN
                                return@reloadOne
                            }
                            bundle.animationProgress = vmodel.imgBundles.map { it.animationProgress }.maxOrNull() ?: 0
                            with(vmodel.animationLooper) {
                                receiveNewFrames(loader, frameSequence, isOffline)
                                resume(context, animationCoversMinutes, rateMinsPerSec, freezeTimeMillis)
                            }
                            bundle.status = SHOWING
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            severe(CC_PRIVATE, e) { "Failed to load animation for ${loader.title}" }
                            bundle.status = BROKEN
                        }
                    }
                }
            }
        }
    }

    private fun switchActionBarVisible() = run { activity?.switchActionBarVisible(); true }
}
