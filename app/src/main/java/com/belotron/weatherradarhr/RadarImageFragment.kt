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
import android.view.ViewGroup
import android.widget.ImageView
import java.nio.ByteBuffer

val images = arrayOf(
        ImgDescriptor(0, "HR", R.id.img_view_kradar,
                "http://vrijeme.hr/kradar-anim.gif",
                15),
        ImgDescriptor(1, "SLO", R.id.img_view_lradar,
                "http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar_anim.gif",
                10)
)

class ImgDescriptor(
        val index: Int,
        val title: String,
        val imgViewId: Int,
        val url: String,
        val minutesPerFrame: Int
) {
    val framesToKeep = Math.ceil(ANIMATION_COVERS_MINUTES.toDouble() / minutesPerFrame).toInt()
    val filename = url.substringAfterLast('/')
}

fun SharedPreferences.frameDelayFactor(): Int = getString("frame_delay", "frameDelay0").let { delayStr ->
    when (delayStr) {
        "frameDelay0" -> 12
        "frameDelay1" -> 26
        "frameDelay2" -> 47
        else -> throw RuntimeException("Invalid animation frameDelay value: $delayStr")
    }
}

fun SharedPreferences.freezeTime() = getString("freeze_time", "freeze0").let { freezeStr ->
    when (freezeStr) {
        "freeze0" -> 1500
        "freeze1" -> 2500
        "freeze2" -> 3500
        else -> throw RuntimeException("Invalid animation duration value: $freezeStr")
    }
}

class RadarImageFragment : Fragment() {
    private val imgViews: Array<ImageView?> = arrayOf(null, null)
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
        val rootView = inflater.inflate(R.layout.image_radar, container, false)
        images.forEach {
            val imgView = rootView.findViewById<ImageView>(it.imgViewId)
            imgViews[it.index] = imgView
            imgView.setOnClickListener {
                val toolbar = activity.actionBar
                if (toolbar.isShowing) {
                    toolbar.hide()
                } else {
                    toolbar.show()
                }
            }
        }
        val mainActivity = activity as MainActivity
        if (mainActivity.didRotate) {
            mainActivity.didRotate = false
        } else {
            startFetchWidgetImages(activity.applicationContext)
            startFetchAnimations()
        }
        return rootView
    }

    override fun onDestroyView() {
        MyLog.i { "RadarImageFragment.onDestroyView" }
        super.onDestroyView()
        imgViews.fill(null)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        MyLog.i { "RadarImageFragment.onCreateOptionsMenu" }
        inflater.inflate(R.menu.main_menu, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        MyLog.i { "RadarImageFragment.onOptionsItemSelected" }
        activity.actionBar.hide()
        when (item.itemId) {
            R.id.refresh -> {
                if (imgViews[0] != null) {
                    startFetchAnimations()
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
        animationLooper.restart(activity.animationDuration(), activity.frameDelayFactor())
    }

    private fun startFetchAnimations() {
        val context = activity
        val frameDelayFactor = context.frameDelayFactor()
        val animationDuration = context.animationDuration()
        for (desc in images) {
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
                } catch (t: Throwable) {
                    MyLog.e("Failed to load animated GIF ${desc.filename}", t)
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


