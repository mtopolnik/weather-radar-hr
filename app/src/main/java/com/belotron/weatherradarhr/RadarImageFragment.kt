package com.belotron.weatherradarhr

import android.app.Fragment
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.preference.PreferenceManager
import android.preference.PreferenceManager.getDefaultSharedPreferences
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import java.nio.ByteBuffer

const val KEY_FRAME_DELAY = "frame_delay"
const val DEFAULT_STR_FRAME_DELAY = "frameDelay0"
const val DEFAULT_VALUE_FRAME_DELAY = 12

const val KEY_FREEZE_TIME = "freeze_time"
const val DEFAULT_STR_FREEZE_TIME = "freeze0"
const val DEFAULT_VALUE_FREEZE_TIME = 1500

val imgDescs = arrayOf(
        ImgDescriptor(0, "HR", R.id.img_kradar, R.id.progress_bar_kradar, R.id.broken_img_kradar,
                "http://vrijeme.hr/kradar-anim.gif",
                15),
        ImgDescriptor(1, "SLO", R.id.img_lradar, R.id.progress_bar_lradar, R.id.broken_img_lradar,
                "http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar_anim.gif",
                10)
)

class ImgDescriptor(
        val index: Int,
        val title: String,
        val imgViewId: Int,
        val progressBarId: Int,
        val brokenImgViewId: Int,
        val url: String,
        val minutesPerFrame: Int
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

private fun SharedPreferences.replaceSetting(keyStr: String, valStr: String, value: Int): Int {
    val e = edit()
    e.putString(keyStr, valStr)
    e.apply()
    return value
}

class RadarImageFragment : Fragment() {
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
            brokenImgViews[it.index] = rootView.findViewById<ImageView>(it.brokenImgViewId).also {
                it.setSwitchActionBarListener()
                it.visibility = GONE
            }
            progressBars[it.index] = rootView.findViewById<ProgressBar>(it.progressBarId).also {
                it.setSwitchActionBarListener()
            }
            imgViews[it.index] = rootView.findViewById<ImageView>(it.imgViewId).also {
                it.setSwitchActionBarListener()
            }
        }
        return rootView
    }

    override fun onDestroyView() {
        MyLog.i { "RadarImageFragment.onDestroyView" }
        super.onDestroyView()
        imgViews.fill(null)
        brokenImgViews.fill(null)
        progressBars.fill(null)
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
                if (imgViews[0] != null) {
                    startReloadAnimations()
                }
            }
            R.id.settings -> {
                startActivity(Intent(activity, SettingsActivity::class.java))
            }
            R.id.about -> Unit
        }
        return true
    }

    override fun onPause() {
        MyLog.i { "RadarImageFragment.onPause" }
        super.onPause()
        animationLooper.cancel()
    }

    override fun onResume() {
        MyLog.i { "RadarImageFragment.onResume" }
        super.onResume()
        if (animationLooper.animators.any { it != null }) {
            imgDescs.forEach {
                progressBars[it.index]?.visibility = GONE
                val hasImage = animationLooper.animators[it.index] != null
                imgViews[it.index]?.visibility = if (hasImage) VISIBLE else GONE
                brokenImgViews[it.index]?.visibility = if (hasImage) GONE else VISIBLE
            }
            animationLooper.restart(activity.animationDuration(), activity.frameDelayFactor())
        } else {
            startReloadAnimations()
            startFetchWidgetImages(activity.applicationContext)
        }
    }

    private fun View.setSwitchActionBarListener() = setOnClickListener {
        val toolbar = activity.actionBar
        if (toolbar.isShowing) {
            toolbar.hide()
        } else {
            toolbar.show()
        }
    }

    private fun startReloadAnimations() {
        imgDescs.forEach {
            progressBars[it.index]?.visibility = VISIBLE
            brokenImgViews[it.index]?.visibility = GONE
            imgViews[it.index]?.visibility = GONE
        }
        val context = activity
        val frameDelayFactor = context.frameDelayFactor()
        val animationDuration = context.animationDuration()
        for (desc in imgDescs) {
            start coroutine@ {
                try {
                    val (_, imgBytes) = try {
                        fetchUrl(context, desc.url, onlyIfNew = false)
                    } catch (e: ImageFetchException) {
                        Pair(0L, e.cached)
                    }
                    if (imgBytes == null || imgViews[desc.index] == null) {
                        return@coroutine
                    }
                    val buf = ByteBuffer.wrap(imgBytes)
                    editGif(buf, desc.framesToKeep)
                    animationLooper.animators[desc.index] = GifAnimator(buf.toArray(), imgViews, desc)
                    animationLooper.restart(animationDuration, frameDelayFactor)
                    progressBars[desc.index]?.visibility = GONE
                    imgViews[desc.index]?.visibility = VISIBLE
                    activity.actionBar.hide()
                } catch (t: Throwable) {
                    MyLog.e("Failed to load animated GIF ${desc.filename}", t)
                    progressBars[desc.index]?.visibility = GONE
                    brokenImgViews[desc.index]?.visibility = VISIBLE
                }
            }
        }
    }
}

private fun ByteBuffer.toArray() = ByteArray(this.remaining()).also { this.get(it) }

private fun Context.animationDuration(): Int {
    val prefs = PreferenceManager.getDefaultSharedPreferences(this)
    return ANIMATION_COVERS_MINUTES * prefs.frameDelayFactor() + prefs.freezeTime()
}

private fun Context.frameDelayFactor(): Int {
    return getDefaultSharedPreferences(this).frameDelayFactor()
}


