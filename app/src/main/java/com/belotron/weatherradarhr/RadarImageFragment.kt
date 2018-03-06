package com.belotron.weatherradarhr

import android.app.Fragment
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.View.GONE
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
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
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.launch
import java.util.concurrent.TimeUnit

private val RELOAD_ON_RESUME_IF_OLDER_THAN_MILLIS = TimeUnit.MINUTES.toMillis(5)

val imgDescs = arrayOf(
        ImgDescriptor(0, "HR", "http://vrijeme.hr/kradar-anim.gif", 15,
                R.id.img_kradar, R.id.progress_bar_kradar, R.id.broken_img_kradar,
                R.id.text_kradar, KradarOcr::ocrKradarTimestamp),
        ImgDescriptor(1, "SLO", "http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar_anim.gif", 10,
                R.id.img_lradar, R.id.progress_bar_lradar, R.id.broken_img_lradar,
                R.id.text_lradar, LradarOcr::ocrLradarTimestamp)
)

class ImgDescriptor(
        val index: Int,
        val title: String,
        val url: String,
        val minutesPerFrame: Int,
        val imgViewId: Int,
        val progressBarId: Int,
        val brokenImgViewId: Int,
        val textViewId: Int,
        val ocrTimestamp: (Bitmap) -> Long
) {
    val framesToKeep = Math.ceil(ANIMATION_COVERS_MINUTES.toDouble() / minutesPerFrame).toInt()
    val filename = url.substringAfterLast('/')
}

class ImageBundle {
    var textView: TextView? = null
    var imgView: ImageView? = null
    private var brokenImgView: ImageView? = null
    private var progressBar: ProgressBar? = null

    var status = UNKNOWN
        set(value) {
            field = value
            progressBar?.setVisible(value == LOADING)
            imgView?.setVisible(value == SHOWING)
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
        that.textView = this.textView!!
        that.imgView = this.imgView!!
        that.brokenImgView = this.brokenImgView!!
        that.progressBar = this.progressBar!!
        that.status = this.status
    }

    fun clear() {
        destroyViews()
        status = HIDDEN
    }

    fun destroyViews() {
        this.textView = null
        this.imgView = null
        this.brokenImgView = null
        this.progressBar = null
    }

    fun restoreViews(
            textView: TextView,
            imgView: ImageView,
            brokenImgView: ImageView,
            progressBar: ProgressBar
    ) {
        this.textView = textView
        this.imgView = imgView
        this.brokenImgView = brokenImgView
        this.progressBar = progressBar
        this.status = status // reapplies the status to view visibility
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
        wasFastResume = savedInstanceState?.wasFastResume ?: false
        val rootView = inflater.inflate(R.layout.fragment_radar, container, false)
        this.rootView = rootView
        vGroupOverview = rootView.findViewById(R.id.radar_overview)
        vGroupFullScreen = rootView.findViewById(R.id.radar_zoomed)
        fullScreenBundle.restoreViews(
                textView = rootView.findViewById(R.id.text_radar_zoomed),
                imgView = rootView.findViewById<TouchImageView>(R.id.img_radar_zoomed).apply {
                    setOnDoubleTapListener(object: SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(e: MotionEvent) = switchActionBarVisible()
                        override fun onDoubleTap(e: MotionEvent) = run { exitFullScreen(); true }
                    })
                },
                brokenImgView = rootView.findViewById(R.id.broken_img_zoomed),
                progressBar = rootView.findViewById(R.id.progress_bar_zoomed)
        )
        imgDescs.forEachIndexed { i, desc -> imgBundles[i].restoreViews(
                textView = rootView.findViewById(desc.textViewId),
                imgView = rootView.findViewById<ImageView>(desc.imgViewId).also { imgView ->
                    val gl = object : SimpleOnGestureListener() {
                        override fun onSingleTapConfirmed(e: MotionEvent) = switchActionBarVisible()
                        override fun onDoubleTap(e: MotionEvent) = enterFullScreen(i, e)
                    }
                    GestureDetector(activity, gl).let {
                        imgView.setOnTouchListener { _, e -> it.onTouchEvent(e); true }
                    }
                },
                brokenImgView = rootView.findViewById<ImageView>(desc.brokenImgViewId).apply {
                    setOnClickListener { switchActionBarVisible() }
                    visibility = GONE
                },
                progressBar = rootView.findViewById<ProgressBar>(desc.progressBarId).apply {
                    setOnClickListener { switchActionBarVisible() }
                }
        ) }
        initFullScreenBundle()
        updateFullScreenVisibility()
        updateAdVisibility()
        return rootView
    }

    override fun onResume() {
        info { "RadarImageFragment.onResume" }
        super.onResume()
        lastReloadedTimestamp = activity.sharedPrefs.lastReloadedTimestamp
        val isTimeToReload = System.currentTimeMillis() > lastReloadedTimestamp + RELOAD_ON_RESUME_IF_OLDER_THAN_MILLIS
        val statusIsKnown = imgBundles.none { it.status == UNKNOWN }
        if (statusIsKnown && (wasFastResume || !isTimeToReload)) {
            with (activity.sharedPrefs) {
                animationLooper.restart(animationDurationMillis, rateMinsPerSec)
            }
        } else {
            info { "Reloading animations" }
            startReloadAnimations(if (isTimeToReload) UP_TO_DATE else PREFER_CACHED)
            activity.startFetchWidgetImages()
        }
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
    }

    override fun onPause() {
        info { "RadarImageFragment.onPause" }
        super.onPause()
        wasFastResume = false
        animationLooper.stop()
        activity.sharedPrefs.lastReloadedTimestamp = lastReloadedTimestamp
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
            R.id.about -> launch(UI) {
                showAboutDialogFragment(activity)
                updateAdVisibility()
                switchActionBarVisible()
            }
        }
        return true
    }

    private fun enterFullScreen(index: Int, e: MotionEvent): Boolean {
        indexOfImgInFullScreen = index
        initFullScreenBundle()
        updateFullScreenVisibility()
        launch(UI) {
            fullScreenBundle.imgView?.let { it as TouchImageView }?.apply {
                resetToNeverDrawn()
                setImageBitmap(imgBundles[index].bitmap)
                awaitOnDraw()
                animateZoomEnter(e)
            }
        }
        return true
    }

    fun exitFullScreen() {
        val index = indexOfImgInFullScreen ?: return
        indexOfImgInFullScreen = null
        launch(UI) {
            val target = imgBundles[index]
            if (target.status == SHOWING) {
                target.imgView?.let { it as TouchImageView }?.animateZoomExit()
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

    private fun initFullScreenBundle() {
        val target = imgBundles[indexOfImgInFullScreen ?: return]
        target.copyTo(stashedImgBundle)
        with(fullScreenBundle) {
            updateFrom(target)
            copyTo(target)
        }
    }

    private fun updateAdVisibility() {
        val adView = rootView?.findViewById<AdView>(R.id.adView) ?: return
        val adsEnabled = activity.sharedPrefs.adsEnabled
        adView.setVisible(adsEnabled)
        if (adsEnabled) {
            adView.loadAd(AdRequest.Builder().build())
        }
    }

    private fun startReloadAnimations(fetchPolicy: FetchPolicy) {
        val context = activity ?: return
        imgDescs.forEach {
            imgBundles[it.index].status = LOADING
        }
        val frameDelayFactor = context.sharedPrefs.rateMinsPerSec
        val animationDuration = context.sharedPrefs.animationDurationMillis
        for (desc in imgDescs) {
            val bundle = imgBundles[desc.index]
            launch(UI) {
                try {
                    val (lastModified, imgBytes) = try {
                        fetchUrl(context, desc.url, fetchPolicy)
                    } catch (e: ImageFetchException) {
                        Pair(0L, e.cached)
                    }
                    if (imgBytes == null) {
                        bundle.status = BROKEN
                        return@launch
                    }
                    lastReloadedTimestamp = System.currentTimeMillis()
                    val gifData = editGif(imgBytes, desc.framesToKeep)
                    with (animationLooper) {
                        receiveNewGif(desc, gifData, isOffline = lastModified == 0L)
                        restart(animationDuration, frameDelayFactor)
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

