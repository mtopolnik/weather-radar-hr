package com.belotron.weatherradarhr

import android.annotation.SuppressLint
import android.app.Fragment
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Bundle
import android.preference.PreferenceManager
import android.preference.PreferenceManager.getDefaultSharedPreferences
import android.view.GestureDetector
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
import com.belotron.weatherradarhr.ImgStatus.BROKEN
import com.belotron.weatherradarhr.ImgStatus.LOADING
import com.belotron.weatherradarhr.ImgStatus.SHOWING
import java.nio.ByteBuffer

const val KEY_FRAME_DELAY = "frame_delay"
const val DEFAULT_STR_FRAME_DELAY = "frameDelay0"
const val DEFAULT_VALUE_FRAME_DELAY = 12

const val KEY_FREEZE_TIME = "freeze_time"
const val DEFAULT_STR_FREEZE_TIME = "freeze0"
const val DEFAULT_VALUE_FREEZE_TIME = 1500

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

fun SharedPreferences.frameDelayFactor() = getString(KEY_FRAME_DELAY, DEFAULT_STR_FRAME_DELAY).let { delayStr ->
    when (delayStr) {
        DEFAULT_STR_FRAME_DELAY -> return DEFAULT_VALUE_FRAME_DELAY
        "frameDelay1" -> return 26
        "frameDelay2" -> return 47
        else -> replaceSetting(KEY_FRAME_DELAY, DEFAULT_STR_FRAME_DELAY, DEFAULT_VALUE_FRAME_DELAY)
    }
}

fun SharedPreferences.freezeTime() = getString(KEY_FREEZE_TIME, DEFAULT_STR_FREEZE_TIME).let { freezeStr ->
    when (freezeStr) {
        DEFAULT_STR_FREEZE_TIME -> return DEFAULT_VALUE_FREEZE_TIME
        "freeze1" -> return 2500
        "freeze2" -> return 3500
        else -> replaceSetting(KEY_FREEZE_TIME, DEFAULT_STR_FREEZE_TIME, DEFAULT_VALUE_FREEZE_TIME)
    }
}

@SuppressLint("CommitPrefEdits")
private fun SharedPreferences.replaceSetting(keyStr: String, valStr: String, value: Int): Int {
    with(edit()) {
        putString(keyStr, valStr)
        apply()
    }
    return value
}

class RadarImageFragment : Fragment() {
    private val textViews: Array<TextView?> = arrayOf(null, null)
    private val imgViews: Array<ImageView?> = arrayOf(null, null)
    private val brokenImgViews: Array<ImageView?> = arrayOf(null, null)
    private val progressBars: Array<ProgressBar?> = arrayOf(null, null)
    private lateinit var animationLooper: AnimationLooper

    override fun onCreate(savedInstanceState: Bundle?) {
        MyLog.i { "RadarImageFragment.onCreate" }
        super.onCreate(savedInstanceState)
        retainInstance = true
        setHasOptionsMenu(true)
        animationLooper = AnimationLooper()
    }

    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        MyLog.i { "RadarImageFragment.onCreateView" }
        val rootView = inflater.inflate(R.layout.fragment_radar, container, false)
        imgDescs.forEach {
            textViews[it.index] = rootView.findViewById(it.textViewId)
            brokenImgViews[it.index] = rootView.findViewById<ImageView>(it.brokenImgViewId).also {
                it.setOnClickListener { switchActionBarVisible() }
                it.visibility = GONE
            }
            progressBars[it.index] = rootView.findViewById<ProgressBar>(it.progressBarId).also {
                it.setOnClickListener { switchActionBarVisible() }
            }
            imgViews[it.index] = rootView.findViewById<TouchImageView>(it.imgViewId).also {
                it.setOnDoubleTapListener(object: GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapConfirmed(e: MotionEvent?): Boolean = switchActionBarVisible()
                })
            }
        }
        return rootView
    }

    override fun onResume() {
        MyLog.i { "RadarImageFragment.onResume" }
        super.onResume()
        val isTimeToReload = System.currentTimeMillis() > lastReloadedTimestamp + RELOAD_ON_RESUME_IF_OLDER_THAN_MILLIS
        val noAnimationsLoaded = animationLooper.animators.all { it == null }
        if (noAnimationsLoaded || isTimeToReload) {
            MyLog.i {
                "Reloading animations. Was it time to reload? $isTimeToReload." +
                        " No animations loaded? $noAnimationsLoaded"
            }
            startReloadAnimations()
            startFetchWidgetImages(activity.applicationContext)
        } else {
            imgDescs.forEach {
                val animator = animationLooper.animators[it.index]?.apply {
                    setAgeText(textViews[it.index]!!)
                }
                setImageStatus(it.index, if (animator != null) SHOWING else BROKEN)
            }
            animationLooper.restart(activity.animationDuration(), activity.frameDelayFactor())
        }
    }

    override fun onDestroyView() {
        MyLog.i { "RadarImageFragment.onDestroyView" }
        super.onDestroyView()
        textViews.fill(null)
        imgViews.fill(null)
        brokenImgViews.fill(null)
        progressBars.fill(null)
    }

    override fun onPause() {
        MyLog.i { "RadarImageFragment.onPause" }
        super.onPause()
        animationLooper.cancel()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        MyLog.i { "RadarImageFragment.onCreateOptionsMenu" }
        inflater.inflate(R.menu.main_menu, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        MyLog.i { "RadarImageFragment.onOptionsItemSelected" }
        when (item.itemId) {
            R.id.refresh ->
                if (imgViews[0] != null) {
                    startReloadAnimations()
                }
            R.id.settings -> startActivity(Intent(activity, SettingsActivity::class.java))
            R.id.about -> Unit
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

    private fun startReloadAnimations() {
        imgDescs.forEach {
            setImageStatus(it.index, LOADING)
        }
        val context = activity
        val frameDelayFactor = context.frameDelayFactor()
        val animationDuration = context.animationDuration()
        for (desc in imgDescs) {
            start {
                try {
                    val (_, imgBytes) = try {
                        fetchUrl(context, desc.url, onlyIfNew = false)
                    } catch (e: ImageFetchException) {
                        Pair(0L, e.cached)
                    }
                    if (imgBytes == null || imgViews[desc.index] == null) {
                        return@start
                    }
                    val buf = ByteBuffer.wrap(imgBytes)
                    editGif(buf, desc.framesToKeep)
                    animationLooper.animators[desc.index] = GifAnimator(buf.toArray(), imgViews, desc).apply {
                        setAgeText(textViews[desc.index]!!)
                    }
                    animationLooper.restart(animationDuration, frameDelayFactor)
                    setImageStatus(desc.index, SHOWING)
                    activity.actionBar.hide()
                } catch (t: Throwable) {
                    MyLog.e("Failed to load animated GIF ${desc.filename}", t)
                    setImageStatus(desc.index, BROKEN)
                }
                lastReloadedTimestamp = System.currentTimeMillis()
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

private fun ByteBuffer.toArray() = ByteArray(this.remaining()).also { this.get(it) }

private fun Context.animationDuration(): Int {
    with(PreferenceManager.getDefaultSharedPreferences(this)) {
        return ANIMATION_COVERS_MINUTES * frameDelayFactor() + freezeTime()
    }
}

private fun Context.frameDelayFactor(): Int {
    return getDefaultSharedPreferences(this).frameDelayFactor()
}
