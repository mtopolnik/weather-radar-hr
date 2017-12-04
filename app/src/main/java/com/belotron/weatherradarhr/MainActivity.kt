package com.belotron.weatherradarhr

import android.content.Context
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentActivity
import android.support.v4.app.FragmentManager
import android.support.v4.app.FragmentPagerAdapter
import android.support.v4.view.ViewPager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
import android.webkit.WebSettings.LOAD_NO_CACHE
import android.webkit.WebView
import com.belotron.weatherradarhr.ImageRequest.sendImageRequest
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

private const val LOADING_HTML = "loading.html"
private const val MAIN_HTML = "radar_image.html"

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

class MainActivity : FragmentActivity()  {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(FLAG_FULLSCREEN, FLAG_FULLSCREEN)
        setContentView(R.layout.activity_main)
        val viewPager = findViewById<ViewPager>(R.id.pager)
        viewPager.adapter = FlipThroughRadarImages(supportFragmentManager)
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
            webView = rootView.findViewById(R.id.web_view_radar)!!
            val webView = webView!!
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
            writeTabHtml()
            return rootView
        }

        override fun onResume() {
            super.onResume()
            MyLog.w("onResume")
            updateWidgets(context.applicationContext)
            reloadImages()
        }

        private fun reloadImages() {
            val countdown = AtomicInteger(images.size)
            for (desc in images) {
                sendImageRequest(context, desc.url, onSuccess = { imgBytes, _ ->
                    val buf = ByteBuffer.wrap(imgBytes)
                    val frameDelay = (1.2 * desc.minutesPerFrame).toInt()
                    editGif(buf, frameDelay, desc.framesToKeep)
                    val gifFile = File(context.noBackupFilesDir, desc.filename)
                    FileOutputStream(gifFile).use { out ->
                        out.write(buf.array(), buf.position(), buf.remaining())
                    }
                }, onCompletion = lambda@ {
                    if (countdown.addAndGet(-1) != 0) {
                        return@lambda
                    }
                    val url = tabHtmlFile(context).toURI().toString()
                    val webView = webView!!
                    webView.clearCache(true)
                    webView.loadUrl(url)
                })
            }
        }

        private fun writeTabHtml() {
            val htmlTemplate =
                    BufferedReader(InputStreamReader(context.assets.open(MAIN_HTML))).use { it.readText() }
            BufferedWriter(FileWriter(tabHtmlFile(context))).use { w -> w.write(expandTemplate(htmlTemplate)) }
        }

        private fun expandTemplate(htmlTemplate: String): String {
            val m = Pattern.compile("\\$\\{([^}]+)\\}").matcher(htmlTemplate)
            val sb = StringBuffer()
            while (m.find()) {
                val k = m.group(1)
                if (k.matches("imageFilename(\\d+)".toRegex())) {
                    m.appendReplacement(sb, images[Integer.parseInt(k.substring(13, k.length))].filename)
                } else {
                    throw AssertionError("Invalid key in HTML template: " + k)
                }
            }
            m.appendTail(sb)
            return sb.toString()
        }

        private fun tabHtmlFile(context: Context) = File(context.noBackupFilesDir, "tab0.html")
    }
}
