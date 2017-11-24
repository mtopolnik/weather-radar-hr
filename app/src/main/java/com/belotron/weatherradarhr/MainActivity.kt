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
import java.io.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

const val LOADING_HTML = "loading.html"

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LRadarOCR.initDigitBitmaps(applicationContext)
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
            updateWidgetAndScheduleNext(context.applicationContext, alwaysScheduleNext = false)
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
            val htmlTemplate = readTemplate()
            val htmlFile = tabHtmlFile(context)
            BufferedWriter(FileWriter(htmlFile)).use { w -> w.write(expandTemplate(htmlTemplate)) }
        }

        private fun readTemplate(): String {
            context.assets.open("radar_image.html").use { input ->
                val r = BufferedReader(InputStreamReader(input))
                val buf = CharArray(BUFSIZ)
                val b = StringBuilder(BUFSIZ)
                while (true) {
                    val count = r.read(buf)
                    if (count == -1) break
                    b.append(buf, 0, count)
                }
                return b.toString()
            }
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
