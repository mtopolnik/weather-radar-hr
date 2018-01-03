package com.belotron.weatherradarhr

import android.content.Context
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentManager
import android.support.v4.app.FragmentPagerAdapter
import android.support.v4.view.ViewPager
import android.support.v7.app.AppCompatActivity
import android.text.format.DateUtils.SECOND_IN_MILLIS
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
import android.webkit.WebSettings.LOAD_NO_CACHE
import android.webkit.WebView
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

private const val LOADING_HTML = "loading.html"
private const val MAIN_HTML = "radar_image.html"
private const val KEY_SAVED_TIMESTAMP = "previous-orientation"
private var didRotate = false
private var mainActivity: MainActivity? = null

private val images = arrayOf(
        ImgDescriptor("Puntijarka-Bilogora-Osijek",
                "http://vrijeme.hr/kradar-anim.gif",
                15),
        ImgDescriptor("Lisca",
                "http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar_anim.gif",
                10)
)

class ImgDescriptor(val title: String, val url: String, val minutesPerFrame: Int) {
    val framesToKeep = Math.ceil(ANIMATION_COVERS_MINUTES.toDouble() / minutesPerFrame).toInt()
    val filename = url.substringAfterLast('/')
}

class MainActivity : AppCompatActivity()  {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MyLog.i("onCreate")
        mainActivity = this
        window.setFlags(FLAG_FULLSCREEN, FLAG_FULLSCREEN)
        setContentView(R.layout.activity_main)
        val viewPager = findViewById<ViewPager>(R.id.my_pager)
        viewPager.adapter = FlipThroughRadarImages(supportFragmentManager)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        MyLog.i("onSaveInstanceState")
        super.onSaveInstanceState(outState)
        val timestamp = System.currentTimeMillis()
        outState.putLong(KEY_SAVED_TIMESTAMP, timestamp)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        MyLog.i("onRestoreInstanceState")
        super.onRestoreInstanceState(savedInstanceState)
        val restoredTimestamp = savedInstanceState.getLong(KEY_SAVED_TIMESTAMP)
        if (restoredTimestamp == 0L) {
            return
        }
        val timeDiff = System.currentTimeMillis() - restoredTimestamp
        didRotate =  timeDiff < SECOND_IN_MILLIS
        MyLog.i("Time diff $timeDiff, did rotate? $didRotate")
    }

}

private class FlipThroughRadarImages internal constructor(fm: FragmentManager) : FragmentPagerAdapter(fm) {

    override fun getCount() = 1

    override fun getPageTitle(position: Int) = "Radar"

    override fun getItem(i: Int): Fragment {
        when (i) {
            0 -> return RadarImageFragment()
            else -> throw AssertionError("Invalid tab index: " + i)
        }
    }
}

class RadarImageFragment : Fragment() {

    private var webView: WebView? = null

    override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val rootView = inflater.inflate(R.layout.image_radar, container, false)
        val webView = rootView.findViewById<WebView>(R.id.web_view_radar)!!
//        val gestureDetector = GestureDetector(context, object : SimpleOnGestureListener() {
//            override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
//                val toolbar = mainActivity!!.findViewById<Toolbar>(R.id.my_toolbar)!!
//                toolbar.visibility = if (toolbar.visibility == VISIBLE) INVISIBLE else VISIBLE
//                return true
//            }
//        })
//        webView.setOnTouchListener({_, event -> gestureDetector.onTouchEvent(event) })
        this.webView = webView
        val s = webView.settings
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.cacheMode = LOAD_NO_CACHE
        val loadingHtml = File(context.noBackupFilesDir, LOADING_HTML)
        context.assets.open(LOADING_HTML).use { it.copyTo(FileOutputStream(loadingHtml)) }
        webView.loadUrl(loadingHtml.toURI().toString())
        return rootView
    }

    override fun onResume() {
        super.onResume()
        MyLog.w("onResume")
        writeTabHtml()
        if (didRotate) {
            didRotate = false
            loadImagesInWebView(false)
        } else {
            startFetchWidgetImages(context.applicationContext)
            startFetchAnimations()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        webView = null
    }

    private fun startFetchAnimations() {
        val androidCtx = context
        val countDown = AtomicInteger(images.size)
        for (desc in images) {
            start coroutine@ {
                try {
                    val (_, imgBytes) = try {
                        fetchImage(androidCtx, desc.url, onlyIfNew = false)
                    } catch (e: ImageFetchException) {
                        Pair(0L, e.cached)
                    }
                    if (imgBytes == null || webView == null) {
                        return@coroutine
                    }
                    val buf = ByteBuffer.wrap(imgBytes)
                    val frameDelay = (1.2 * desc.minutesPerFrame).toInt()
                    editGif(buf, frameDelay, desc.framesToKeep)
                    val gifFile = File(androidCtx.noBackupFilesDir, desc.filename)
                    FileOutputStream(gifFile).use {
                        it.write(buf.array(), buf.position(), buf.remaining())
                    }
                } catch (t: Throwable) {
                    MyLog.e("Failed to load animated GIF ${desc.filename}")
                } finally {
                    if (countDown.addAndGet(-1) == 0) {
                        loadImagesInWebView(true)
                    }
                }
            }
        }
    }

    private fun loadImagesInWebView(clearCache: Boolean) {
        val webView = webView ?: return
        val url = tabHtmlFile(context).toURI().toString()
        if (clearCache) {
            webView.clearCache(true)
        }
        webView.loadUrl(url)
    }

    private fun writeTabHtml() {
        val htmlTemplate = BufferedReader(InputStreamReader(context.assets.open(MAIN_HTML))).use { it.readText() }
        BufferedWriter(FileWriter(tabHtmlFile(context))).use { it.write(expandTemplate(htmlTemplate)) }
    }

    private val placeholderRegex = Regex("""\$\{([^}]+)\}""")
    private val imageFilenameRegex = Regex("""imageFilename(\d+)""")

    private fun expandTemplate(htmlTemplate: String): String =
            placeholderRegex.replace(htmlTemplate, { placeholderMatch ->
                val key = placeholderMatch.groupValues[1]
                when (key) {
                    "didRotate" -> "$didRotate"
                    else -> {
                        val fnameMatch = imageFilenameRegex.matchEntire(key)
                                ?: throw AssertionError("Invalid key in HTML template: " + key)
                        images[fnameMatch.groupValues[1].toInt()].filename
                    }
                }
            })

    private fun tabHtmlFile(context: Context) = File(context.noBackupFilesDir, "tab0.html")
}
