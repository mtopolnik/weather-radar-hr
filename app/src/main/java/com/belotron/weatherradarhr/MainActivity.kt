package com.belotron.weatherradarhr

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import com.belotron.weatherradarhr.GifEditor.Companion.editGif
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions.signatureOf
import com.bumptech.glide.request.target.SimpleTarget
import com.bumptech.glide.request.transition.Transition
import com.bumptech.glide.signature.ObjectKey
import java.io.*
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

class MainActivity : FragmentActivity() {
    companion object {

        internal val LOGTAG = "WeatherRadar"

        internal val LOOP_COUNT = 20
        internal val ANIMATION_DURATION = 250

        private val BUFSIZ = 512
        private val ANIMATION_COVERS_MINUTES = 100

        private val images = arrayOf(
                ImgDescriptor("Lisca",
                        "http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar_anim.gif",
                        10)
                ,
                ImgDescriptor("Puntijarka-Bilogora-Osijek",
                        "http://vrijeme.hr/kradar-anim.gif",
                        15)
//                ,
//                ImgDescriptor("Dubrovnik",
//                        "http://vrijeme.hr/dradar-anim.gif",
//                        15)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(FLAG_FULLSCREEN, FLAG_FULLSCREEN)
        setContentView(R.layout.activity_main)
        val viewPager = findViewById<ViewPager>(R.id.pager)
        viewPager.adapter = FlipThroughRadarImages(supportFragmentManager)
    }

    private class FlipThroughRadarImages internal constructor(fm: FragmentManager) : FragmentPagerAdapter(fm) {

        override fun getPageTitle(position: Int): CharSequence {
            return images[position].title
        }

        override fun getItem(i: Int): Fragment {
            when (i) {
                0 -> return RadarImageFragment()
                else -> throw AssertionError("Invalid tab index: " + i)
            }
        }

        override fun getCount(): Int {
            return 1
        }
    }

    class RadarImageFragment : Fragment() {

        private var webView: WebView? = null

        override fun onCreateView(inflater: LayoutInflater,
                                  container: ViewGroup?,
                                  savedInstanceState: Bundle?
        ): View {
            val rootView = inflater.inflate(R.layout.image_radar, container, false)
            webView = rootView.findViewById(R.id.web_view_radar)
            val s = webView!!.settings
            s.setSupportZoom(true)
            s.builtInZoomControls = true
            s.displayZoomControls = false
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
            s.cacheMode = LOAD_NO_CACHE
            writeTabHtml()
            reloadImages()
            return rootView
        }

        override fun onResume() {
            super.onResume()
            MyLog.w("onResume")
            triggerWidgetUpdate()
            reloadImages()
        }

        private fun triggerWidgetUpdate() {
            val intent = Intent(context, MyWidgetProvider::class.java)
            intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            val widgetIDs = AppWidgetManager.getInstance(context)
                    .getAppWidgetIds(ComponentName(context, MyWidgetProvider::class.java))
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIDs)
            context.sendBroadcast(intent)
        }

        private fun reloadImages() {
            val countdown = AtomicInteger(images.size)
            for (desc in images) {
                    Glide.with(context)
                            .downloadOnly()
                            .load(desc.url)
                            .apply(signatureOf(ObjectKey(TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis()))))
                            .into(object : SimpleTarget<File>() {
                                override fun onResourceReady(resource: File, transition: Transition<in File>?) {
                                    try {
                                        val frameDelay = (1.2 * desc.minutesPerFrame).toInt()
                                        val bytes = FileInputStream(resource).readBytes()
                                        val buf = ByteBuffer.wrap(bytes)
                                        editGif(buf, frameDelay, desc.framesToKeep)
                                        val gifFile = File(context.noBackupFilesDir, desc.filename())
                                        FileOutputStream(gifFile).use { out ->
                                            out.write(buf.array(), buf.position(), buf.remaining()) }
                                        if (countdown.addAndGet(-1) == 0) {
                                            val url = tabHtmlFile(context).toURI().toString()
                                            webView!!.clearCache(true)
                                            webView!!.loadUrl(url)
                                        }
                                    } catch (t: Throwable) {
                                        MyLog.e("Error loading GIF " + desc.filename(), t)
                                    }
                                }
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
                    m.appendReplacement(sb, images[Integer.parseInt(k.substring(13, k.length))].filename())
                } else {
                    throw AssertionError("Invalid key in HTML template: " + k)
                }
            }
            m.appendTail(sb)
            return sb.toString()
        }

        private fun tabHtmlFile(context: Context): File {
            return File(context.noBackupFilesDir, "tab0.html")
        }
    }

    private class ImgDescriptor
    internal constructor(internal val title: String, internal val url: String, internal val minutesPerFrame: Int) {
        internal val framesToKeep = Math.ceil(ANIMATION_COVERS_MINUTES.toDouble() / minutesPerFrame).toInt()

        internal fun filename(): String {
            return url.substring(url.lastIndexOf('/') + 1, url.length)
        }
    }
}
