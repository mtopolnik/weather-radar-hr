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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.MenuProvider
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

    val isInFullScreen: Boolean get() = indexOfImgInFullScreen != null
    val locationState = LocationState()
    val fullScreenBundle = ImageBundle()

    var isTrackingTouch = false
    var radarsInUse = listOf<RadarSource>()
    var imgBundles = listOf<ImageBundle>()
    var animationLooper: AnimationLooper? = null
        private set
    var stashedImgBundle = ImageBundle()
    var reloadJob: Job? = null
    var lastReloadedTimestamp = 0L
    var lastAnimationCoversMinutes = 0
    // Serves to avoid IllegalStateException in DialogFragment.show()
    var possibleStateLoss = false

    fun destroyImgBundles() {
        imgBundles.forEach { it.removeViews() }
        fullScreenBundle.removeViews()
        stashedImgBundle.removeViews()
    }

    fun ensureAnimationLooper() {
        if (animationLooper == null) {
            animationLooper = AnimationLooper(this)
        }
        fullScreenBundle.seekBar!!.setOnSeekBarChangeListener(animationLooper)
    }

    fun recreateAnimationLooper() {
        animationLooper?.dispose()
        animationLooper = null
        ensureAnimationLooper()
    }
}

class MainFragment : Fragment(), MenuProvider {

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
    lateinit var rootView: View
    private var wasFastResume = false
    private var onResumeCalled = false
    private var vGroupOverview: ViewGroup? = null
    private var vGroupFullScreen: ViewGroup? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        info { "MainFragment.onCreate" }
        super.onCreate(savedInstanceState)
        vmodel = ViewModelProvider(this)[RadarImageViewModel::class.java]
        val activity = requireActivity()
        activity.addMenuProvider(this, this)
        activity.onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (vmodel.isInFullScreen) {
                    exitFullScreen()
                } else {
                    startActivity(
                        Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                }
            }
        })
        lifecycleScope.launch { checkAndCorrectPermissionsAndSettings() }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        info { "MainFragment.onCreateView" }
        wasFastResume = savedInstanceState?.savedStateRecently ?: false
        rootView = inflater.inflate(R.layout.fragment_radar, container, false)
        rootView.findViewById<Toolbar>(R.id.toolbar).also {
            (requireActivity() as AppCompatActivity).setSupportActionBar(it)
        }
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
                        override fun onDoubleTap(e: MotionEvent) = run { exitFullScreen(); true }
                    })
                },
                seekBar = rootView.findViewById(R.id.radar_seekbar),
                brokenImgView = rootView.findViewById(R.id.broken_img_zoomed),
                progressBar = rootView.findViewById(R.id.progress_bar_zoomed)
        )
        if (resources.configuration.orientation == ORIENTATION_LANDSCAPE) {
            with(vmodel.fullScreenBundle.seekBar!!.layoutParams as FrameLayout.LayoutParams) {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = resources.getDimensionPixelOffset(R.dimen.seekbar_landscape_right_margin)
            }
        }
        val scrollView = rootView.findViewById<ScrollView>(R.id.scrollview)
        val sl = object : SimpleOnScaleGestureListener() {
            private val rect = Rect()
            override fun onScale(detector: ScaleGestureDetector): Boolean = with (detector) {
                if (vmodel.isInFullScreen || scaleFactor <= 1) {
                    return true
                }
                val textViews = vmodel.imgBundles.map { it.textView ?: return true }
                if (textViews.any { ! it.isDescendantOf(scrollView) }) {
                    return true
                }
                // drop(1) shifts the indices down by one, aligning with the check
                // `focusY <= rect.top`, which determines whether the view above the
                // current one is in the focus of the scale gesture
                val imgIndex = textViews.drop(1).mapIndexed { i, textView ->
                    scrollView.offsetDescendantRectToMyCoords(textView, rect.reset())
                    val focusY = scrollView.scrollY + focusY
                    Pair(i, focusY <= rect.top)
                }.find { (_, hasFocus) -> hasFocus }
                    ?.component1()
                    ?: textViews.lastIndex
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
        ScaleGestureDetector(requireActivity(), sl).also {
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
        updateFullScreenVisibility()
        return rootView
    }

    private inner class MainViewListener(
            private val imgIndex: Int,
            private val imgView: ImageView
    ) : SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (!vmodel.isInFullScreen) enterFullScreen(imgIndex, imgView, e.x, e.y)
            return true
        }
    }

    override fun onResume() {
        info { "MainFragment.onResume" }
        val aWhileAgo = System.currentTimeMillis() - A_WHILE_IN_MILLIS
        super.onResume()
        vmodel.possibleStateLoss = false
        val activity = requireActivity()
        val oldRadarsInUse = vmodel.radarsInUse
        val mainPrefs = activity.mainPrefs
        vmodel.radarsInUse = mainPrefs.configuredRadarSources().takeWhile { it != null }.filterNotNull()
        val radarsChanged = vmodel.radarsInUse != oldRadarsInUse
        if (radarsChanged) {
            info { "New radars in use: " + vmodel.radarsInUse.joinToString { it.name } }
            if (vmodel.isInFullScreen) {
                exitFullScreen()
            }
        }
        if (radarsChanged || vmodel.imgBundles.isEmpty()) {
            vmodel.imgBundles = List(vmodel.radarsInUse.size) { ImageBundle() }
            recreateRadarViews(layoutInflater)
            vmodel.recreateAnimationLooper()
        } else {
            if (!onResumeCalled) {
                recreateRadarViews(layoutInflater)
            }
            vmodel.ensureAnimationLooper()
        }
        onResumeCalled = true
        val animationLengthChanged = (mainPrefs.animationCoversMinutes != vmodel.lastAnimationCoversMinutes)
        val timeToReload = vmodel.lastReloadedTimestamp < aWhileAgo
        val animationIsShowing = vmodel.imgBundles.all { it.status !in EnumSet.of(UNKNOWN, BROKEN) }
        info {
            "animationIsShowing $animationIsShowing radarsChanged $radarsChanged " +
                    "animationLengthChanged $animationLengthChanged wasFastResume $wasFastResume timeToReload $timeToReload"
        }
        if (animationIsShowing && !(radarsChanged || animationLengthChanged) && (wasFastResume || !timeToReload)) {
            with(mainPrefs) {
                vmodel.animationLooper!!.resume(
                    activity,
                    newAnimationCoversMinutes = animationCoversMinutes,
                    newRateMinsPerSec = rateMinsPerSec,
                    newFreezeTimeMillis = freezeTimeMillis,
                    newSeekbarVibrate = seekbarVibrate
                )
            }
        } else {
            startReloadAnimations(PREFER_CACHED)
            lifecycleScope.launch {
                vmodel.reloadJob?.join()
                if (timeToReload || radarsChanged) {
                    info { "Time to reload animations" }
                    startReloadAnimations(UP_TO_DATE)
                }
            }
            refreshWidgetsInForeground()
        }
        lifecycleScope.launch { vmodel.locationState.trackLocationEnablement(activity) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun recreateRadarViews(inflater: LayoutInflater) {
        val radarList = rootView.findViewById<ViewGroup>(R.id.radar_img_container)
        radarList.removeViews(1, radarList.childCount - 1)
        vmodel.imgBundles.forEachIndexed { i, imgBundle ->
            val radarGroup = inflater.inflate(R.layout.radar_frame, radarList, false)
            radarList.addView(radarGroup)
            val viewGroup = radarGroup.findViewById<ViewGroup>(R.id.radar_image_group)
            val imgView = radarGroup.findViewById<ImageViewWithLocation>(R.id.radar_img)
            val textView = radarGroup.findViewById<TextView>(R.id.radar_img_title_text)
            val brokenImgView = radarGroup.findViewById<ImageView>(R.id.radar_broken_img)
            val progressBar = radarGroup.findViewById<ProgressBar>(R.id.radar_progress_bar)
            imgBundle.restoreViews(
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
                    mapShape = vmodel.radarsInUse[i].mapShape
                },
                seekBar = null,
                brokenImgView = brokenImgView.apply {
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
        setupFullScreenBundle()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        info { "MainFragment.onSaveInstanceState" }
        outState.recordSavingTime()
        vmodel.possibleStateLoss = true
    }

    override fun onDestroyView() {
        info { "MainFragment.onDestroyView" }
        super.onDestroyView()
        vmodel.destroyImgBundles()
    }

    override fun onPause() {
        info { "MainFragment.onPause" }
        super.onPause()
        wasFastResume = false
        vmodel.animationLooper?.stop()
        with(requireActivity()) {
            stopReceivingAzimuthUpdates(vmodel.locationState)
            stopReceivingLocationUpdatesFg()
            if (!anyWidgetInUse()) {
                stopReceivingLocationUpdatesBg()
            }
            vmodel.lastAnimationCoversMinutes = mainPrefs.animationCoversMinutes
        }
    }

    override fun onStop() {
        info { "MainFragment.onStop" }
        super.onStop()
        vmodel.possibleStateLoss = true
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        info { "MainFragment.onCreateMenu" }
        val tb = rootView.findViewById<Toolbar>(R.id.toolbar)
        tb.inflateMenu(R.menu.main_menu)
        tb.menu.findItem(R.id.widget_log_enabled).isChecked = privateLogEnabled
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        info { "MainFragment.onMenuItemSelected" }
        val activity = activity as AppCompatActivity
        when (item.itemId) {
            R.id.edit_radars -> {
                startActivity(Intent(activity, AddRemoveRadarActivity::class.java))
            }
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
        }
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
            vmodel.animationLooper!!.resume(activity)
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
                vmodel.animationLooper!!.resume(activity)
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
        vmodel.imgBundles.forEach { it.status = LOADING }
        val mainPrefs = context.mainPrefs
        val animationCoversMinutes = mainPrefs.animationCoversMinutes
        val rateMinsPerSec = mainPrefs.rateMinsPerSec
        val freezeTimeMillis = mainPrefs.freezeTimeMillis
        val seekbarVibrate = mainPrefs.seekbarVibrate
        vmodel.reloadJob?.cancel()
        vmodel.reloadJob = lifecycleScope.launch {
            supervisorScope {
                vmodel.imgBundles.forEachIndexed { positionInUI, bundle ->
                    val radar = vmodel.radarsInUse[positionInUI]
                    launch {
                        try {
                            val loader = radar.frameSequenceLoader
                            loader.incrementallyFetchFrameSequence(
                                context, animationCoversMinutes, fetchPolicy
                            ).collect { frameSequence ->
                                if (frameSequence == null) {
                                    bundle.status = BROKEN
                                    return@collect
                                }
                                bundle.animationProgress =
                                    vmodel.imgBundles.maxOfOrNull { it.animationProgress } ?: 0
                                with(vmodel.animationLooper!!) {
                                    receiveNewFrames(radar.title, positionInUI, loader, frameSequence)
                                    resume(context,
                                        animationCoversMinutes, rateMinsPerSec, freezeTimeMillis, seekbarVibrate)
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            severe(CC_PRIVATE, e) { "Failed to load animation for ${radar.title}" }
                            bundle.status = BROKEN
                        }
                        bundle.status = SHOWING
                    }
                }
            }
        }
    }
}
