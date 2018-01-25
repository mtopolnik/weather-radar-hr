package com.belotron.weatherradarhr

import android.app.Fragment
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
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
        ImgDescriptor("Puntijarka-Bilogora-Osijek",
                "http://vrijeme.hr/kradar-anim.gif",
                15),
        ImgDescriptor("Lisca",
                "http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar_anim.gif",
                10)
)

class ImgDescriptor(
        val title: String,
        val url: String,
        val minutesPerFrame: Int
) {
    val framesToKeep = Math.ceil(ANIMATION_COVERS_MINUTES.toDouble() / minutesPerFrame).toInt()
    val filename = url.substringAfterLast('/')
}

class ImgContext(
        imgDesc: ImgDescriptor,
        private val prefs: SharedPreferences
) {
    val frameDelay = (frameDelayFactor() * imgDesc.minutesPerFrame).toInt()
    val animationDuration = (imgDesc.framesToKeep - 1) * frameDelay + freezeTime()

    private fun frameDelayFactor(): Float = prefs.getString("frame_delay", "frameDelay0").let { delayStr ->
        when (delayStr) {
            "frameDelay0" -> 1.2f
            "frameDelay1" -> 2.6f
            "frameDelay2" -> 4.8f
            else -> throw RuntimeException("Invalid animation frameDelay value: $delayStr")
        }
    }

    private fun freezeTime() = prefs.getString("freeze_time", "freeze0").let { freezeStr ->
        when (freezeStr) {
            "freeze0" -> 150
            "freeze1" -> 250
            "freeze2" -> 350
            else -> throw RuntimeException("Invalid animation duration value: $freezeStr")
        }
    }
}

fun main(args: Array<String>) {
    println(1.2f * 10)
    println(1.2f * 15)
}

class RadarImageFragment : Fragment() {
    private val imgViews: Array<ImageView?> = arrayOf(null, null)

    override fun onCreate(savedInstanceState: Bundle?) {
        MyLog.i("RadarImageFragment.onCreate")
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        MyLog.i("RadarImageFragment.onCreateOptionsMenu")
        inflater.inflate(R.menu.main_menu, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        MyLog.i("RadarImageFragment.onOptionsItemSelected")
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

    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        MyLog.i("RadarImageFragment.onCreateView")
        val rootView = inflater.inflate(R.layout.image_radar, container, false)
        imgViews[0] = rootView.findViewById(R.id.img_view_kradar)
        imgViews[1] = rootView.findViewById(R.id.img_view_lradar)
        return rootView
    }

    override fun onResume() {
        super.onResume()
        MyLog.w("RadarImageFragment.onResume")
        val mainActivity = activity as MainActivity
        if (mainActivity.didRotate) {
            mainActivity.didRotate = false
        } else {
            startFetchWidgetImages(activity.applicationContext)
            startFetchAnimations()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        imgViews.fill(null)
    }

    private fun startFetchAnimations() {
        val context = activity
        for ((i, desc) in images.withIndex()) {
            start coroutine@ {
                try {
                    val (_, imgBytes) = try {
                        fetchUrl(context, desc.url, onlyIfNew = false)
                    } catch (e: ImageFetchException) {
                        Pair(0L, e.cached)
                    }
                    if (imgBytes == null || imgViews[i] == null) {
                        return@coroutine
                    }
                    val buf = ByteBuffer.wrap(imgBytes)
                    val imgContext = ImgContext(desc, getDefaultSharedPreferences(activity))
                    editGif(buf, imgContext.frameDelay, imgContext.animationDuration, desc.framesToKeep)
                    Animator(buf.toArray(), imgViews, i).animate()
                } catch (t: Throwable) {
                    MyLog.e("Failed to load animated GIF ${desc.filename}", t)
                }
            }
        }
    }
}

private fun ByteBuffer.toArray() = ByteArray(this.remaining()).also { this.get(it) }
