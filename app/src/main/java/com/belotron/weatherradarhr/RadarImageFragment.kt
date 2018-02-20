package com.belotron.weatherradarhr

import android.annotation.SuppressLint
import android.app.Fragment
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
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
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.belotron.weatherradarhr.FetchPolicy.PREFER_CACHED
import com.belotron.weatherradarhr.FetchPolicy.UP_TO_DATE
import com.belotron.weatherradarhr.ImgStatus.BROKEN
import com.belotron.weatherradarhr.ImgStatus.LOADING
import com.belotron.weatherradarhr.ImgStatus.SHOWING
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import java.util.concurrent.TimeUnit

private val RELOAD_ON_RESUME_IF_OLDER_THAN_MILLIS = TimeUnit.MINUTES.toMillis(5)

private const val TAG_ABOUT = "dialog_about"

private const val KEY_LAST_RELOADED_TIMESTAMP = "last-reloaded-timestamp"
private const val KEY_FREEZE_TIME = "freeze_time"
private const val KEY_FRAME_DELAY = "frame_delay"

private const val DEFAULT_STR_FRAME_DELAY = "frameDelay0"
private const val DEFAULT_VALUE_FRAME_DELAY = 12

private const val DEFAULT_STR_FREEZE_TIME = "freeze0"
private const val DEFAULT_VALUE_FREEZE_TIME = 1500

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

private val SharedPreferences.frameDelayFactor: Int get() =
    getString(KEY_FRAME_DELAY, DEFAULT_STR_FRAME_DELAY).let { delayStr ->
        when (delayStr) {
            DEFAULT_STR_FRAME_DELAY -> return DEFAULT_VALUE_FRAME_DELAY
            "frameDelay1" -> return 26
            "frameDelay2" -> return 47
            else -> replaceSetting(KEY_FRAME_DELAY, DEFAULT_STR_FRAME_DELAY, DEFAULT_VALUE_FRAME_DELAY)
        }
    }

private val SharedPreferences.freezeTime: Int get() =
    getString(KEY_FREEZE_TIME, DEFAULT_STR_FREEZE_TIME).let { freezeStr ->
        when (freezeStr) {
            DEFAULT_STR_FREEZE_TIME -> return DEFAULT_VALUE_FREEZE_TIME
            "freeze1" -> return 2500
            "freeze2" -> return 3500
            else -> replaceSetting(KEY_FREEZE_TIME, DEFAULT_STR_FREEZE_TIME, DEFAULT_VALUE_FREEZE_TIME)
        }
    }

class RadarImageFragment : Fragment() {
    private val textViews: Array<TextView?> = arrayOf(null, null)
    private val imgViews: Array<ImageView?> = arrayOf(null, null)
    private val brokenImgViews: Array<ImageView?> = arrayOf(null, null)
    private val progressBars: Array<ProgressBar?> = arrayOf(null, null)
    private lateinit var rootView: View
    private lateinit var animationLooper: AnimationLooper
    private lateinit var vGroupOverview: ViewGroup
    private lateinit var vGroupFullScreen: ViewGroup
    private lateinit var imgViewFullScreen: TouchImageView
    private lateinit var textViewFullScreen: TextView
    private var indexOfImgInFullScreen: Int? = null
    private var lastReloadedTimestamp = 0L


    override fun onCreate(savedInstanceState: Bundle?) {
        MyLog.i { "RadarImageFragment.onCreate" }
        super.onCreate(savedInstanceState)
        retainInstance = true
        setHasOptionsMenu(true)
        animationLooper = AnimationLooper(2)
    }

    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        MyLog.i { "RadarImageFragment.onCreateView" }
        rootView = inflater.inflate(R.layout.fragment_radar, container, false)
        vGroupOverview = rootView.findViewById(R.id.radar_overview)
        vGroupFullScreen = rootView.findViewById(R.id.radar_zoomed)
        imgViewFullScreen = rootView.findViewById(R.id.img_radar_zoomed)
        imgViewFullScreen.setOnDoubleTapListener(object: SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent) = switchActionBarVisible()
            override fun onDoubleTap(e: MotionEvent) = exitFullScreen()
        })
        textViewFullScreen = rootView.findViewById(R.id.text_radar_zoomed)
        imgDescs.forEachIndexed { i, desc ->
            textViews[i] = rootView.findViewById(desc.textViewId)
            progressBars[i] = rootView.findViewById<ProgressBar>(desc.progressBarId).also {
                it.setOnClickListener { switchActionBarVisible() }
            }
            brokenImgViews[i] = rootView.findViewById<ImageView>(desc.brokenImgViewId).also {
                it.setOnClickListener { switchActionBarVisible() }
                it.visibility = GONE
            }
            imgViews[i] = rootView.findViewById<ImageView>(desc.imgViewId).also { imgView ->
                val gl = object : SimpleOnGestureListener() {
                    override fun onSingleTapConfirmed(e: MotionEvent) = switchActionBarVisible()
                    override fun onDoubleTap(e: MotionEvent) = enterFullScreen(i, e)
                }
                GestureDetector(activity, gl).let {
                    imgView.setOnTouchListener { _, e -> it.onTouchEvent(e); true }
                }
            }
            animationLooper.animators[i]?.imgView = imgViews[i]
        }
        updateFullScreenVisibility()
        val adView = rootView.findViewById<AdView>(R.id.adView)
        if (activity.adsEnabled()) {
            adView.loadAd(AdRequest.Builder().build())
        } else {
            adView.visibility = GONE
        }
        return rootView
    }

    private fun updateFullScreenVisibility() {
        val index = indexOfImgInFullScreen
        val mainActivity = activity as MainActivity
        if (index != null) {
            mainActivity.isFullScreenMode = true
            vGroupFullScreen.visibility = VISIBLE
            vGroupOverview.visibility = GONE
            textViewFullScreen.text = textViews[index]!!.text
            animationLooper.animators[index]!!.imgView = imgViewFullScreen
        } else {
            mainActivity.isFullScreenMode = false
            vGroupFullScreen.visibility = GONE
            vGroupOverview.visibility = VISIBLE
            textViewFullScreen.text = ""
            imgViewFullScreen.setImageDrawable(null)
        }
    }

    private fun enterFullScreen(index: Int, e: MotionEvent): Boolean {
        animationLooper.stop()
        indexOfImgInFullScreen = index
        updateFullScreenVisibility()
        start {
            with(imgViewFullScreen) {
                resetToNeverDrawn()
                setImageDrawable(imgViews[index]!!.drawable)
                awaitOnDraw()
                animateZoomEnter(e)
            }
            animationLooper.animateOne(index)
        }
        return true
    }

    fun exitFullScreen(): Boolean {
        val index = indexOfImgInFullScreen!!
        with(animationLooper) {
            stop()
            animators[index]!!.imgView = imgViews[index]
        }
        start {
            imgViewFullScreen.animateZoomExit()
            indexOfImgInFullScreen = null
            updateFullScreenVisibility()
            animationLooper.restart()
        }
        return true
    }

    override fun onResume() {
        MyLog.i { "RadarImageFragment.onResume" }
        super.onResume()
        lastReloadedTimestamp = activity.sharedPrefs.getLong(KEY_LAST_RELOADED_TIMESTAMP, 0L)
        val isTimeToReload = System.currentTimeMillis() > lastReloadedTimestamp + RELOAD_ON_RESUME_IF_OLDER_THAN_MILLIS
        val noAnimationsLoaded = animationLooper.animators.all { it == null }
        if (isTimeToReload || noAnimationsLoaded) {
            MyLog.i {
                "Reloading animations. Is it time to reload? $isTimeToReload." +
                        " No animations loaded? $noAnimationsLoaded"
            }
            startReloadAnimations(if (isTimeToReload) UP_TO_DATE else PREFER_CACHED)
            startFetchWidgetImages(activity.applicationContext)
        } else {
            imgDescs.indices.forEach { i ->
                val animator = animationLooper.animators[i]?.apply {
                    pushAgeTextToView(textViews[i]!!)
                }
                setImageStatus(i, if (animator != null) SHOWING else BROKEN)
            }
            animationLooper.restart(activity.animationDuration(), activity.sharedPrefs.frameDelayFactor)
        }
    }

    override fun onDestroyView() {
        MyLog.i { "RadarImageFragment.onDestroyView" }
        super.onDestroyView()
        textViews.fill(null)
        imgViews.fill(null)
        animationLooper.animators.forEach { it?.imgView = null }
        brokenImgViews.fill(null)
        progressBars.fill(null)
    }

    override fun onPause() {
        MyLog.i { "RadarImageFragment.onPause" }
        super.onPause()
        animationLooper.stop()
        activity.sharedPrefs.applyUpdate {
            putLong(KEY_LAST_RELOADED_TIMESTAMP, lastReloadedTimestamp)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        MyLog.i { "RadarImageFragment.onCreateOptionsMenu" }
        inflater.inflate(R.menu.main_menu, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        MyLog.i { "RadarImageFragment.onOptionsItemSelected" }
        when (item.itemId) {
            R.id.refresh -> {
                if (activity.adsEnabled()) {
                    rootView.findViewById<AdView>(R.id.adView).loadAd(AdRequest.Builder().build())
                }
                if (imgViews[0] != null) {
                    startReloadAnimations(UP_TO_DATE)
                }
            }
            R.id.settings -> startActivity(Intent(activity, SettingsActivity::class.java))
            R.id.about -> AboutDialogFragment().show(activity.fragmentManager, TAG_ABOUT)
        }
        return true
    }

    private fun switchActionBarVisible():Boolean {
        val toolbar = activity.actionBar
        if (toolbar.isShowing) {
            toolbar.hide()
        } else {
            toolbar.show()
        }
        return true
    }

    private fun startReloadAnimations(fetchPolicy: FetchPolicy) {
        imgDescs.forEach {
            setImageStatus(it.index, LOADING)
        }
        val context = activity
        val frameDelayFactor = context.sharedPrefs.frameDelayFactor
        val animationDuration = context.animationDuration()
        for (desc in imgDescs) {
            start {
                try {
                    val (_, imgBytes) = try {
                        fetchUrl(context, desc.url, fetchPolicy)
                    } catch (e: ImageFetchException) {
                        Pair(0L, e.cached)
                    }
                    if (imgViews[desc.index] == null) {
                        return@start
                    }
                    if (imgBytes == null) {
                        setImageStatus(desc.index, BROKEN)
                        return@start
                    }
                    lastReloadedTimestamp = System.currentTimeMillis()
                    val gifData = editGif(imgBytes, desc.framesToKeep)
                    desc.index.let { i ->
                        animationLooper.animators[i] = GifAnimator(desc, gifData, imgViews[i]).apply {
                            pushAgeTextToView(textViews[i]!!)
                        }
                    }
                    animationLooper.restart(animationDuration, frameDelayFactor)
                    setImageStatus(desc.index, SHOWING)
                    activity.actionBar.hide()
                } catch (t: Throwable) {
                    MyLog.e("Failed to load animated GIF ${desc.filename}", t)
                    setImageStatus(desc.index, BROKEN)
                }
            }
        }
    }

    private fun setImageStatus(i: Int, status: ImgStatus) {
        progressBars[i]?.setVisible(status == LOADING)
        imgViews[i]?.setVisible(status == SHOWING)
        brokenImgViews[i]?.setVisible(status == BROKEN)
    }
}

private fun View.setVisible(state: Boolean) {
    visibility = if (state) VISIBLE else GONE
}

private enum class ImgStatus {
    LOADING, SHOWING, BROKEN
}

private fun Context.animationDuration(): Int {
    with(sharedPrefs) {
        return ANIMATION_COVERS_MINUTES * frameDelayFactor + freezeTime
    }
}

private fun SharedPreferences.replaceSetting(keyStr: String, valStr: String, value: Int): Int {
    applyUpdate { putString(keyStr, valStr) }
    return value
}

@SuppressLint("CommitPrefEdits")
private inline fun SharedPreferences.commitUpdate(block: SharedPreferences.Editor.() -> Unit) {
    with (edit()) {
        block()
        commit()
    }
}

@SuppressLint("CommitPrefEdits")
private inline fun SharedPreferences.applyUpdate(block: SharedPreferences.Editor.() -> Unit) {
    with (edit()) {
        block()
        apply()
    }
}
