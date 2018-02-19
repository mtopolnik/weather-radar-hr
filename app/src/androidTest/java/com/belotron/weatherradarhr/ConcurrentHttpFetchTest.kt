package com.belotron.weatherradarhr

import android.os.Handler
import android.support.test.InstrumentationRegistry
import android.support.test.runner.AndroidJUnit4
import android.util.Log
import com.belotron.weatherradarhr.FetchPolicy.UP_TO_DATE
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ConcurrentHttpFetchTest {

    private val logTag = "wrInstrumentedTest"
    private val appContext = InstrumentationRegistry.getTargetContext()
    private val counter = AtomicInteger()

    @Test
    fun testImageRequest() {
        counter.incrementAndGet()
        Handler(appContext.mainLooper).post( {
            (1..5).forEach {
                startFetch("http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar.gif")
                startFetch("http://vrijeme.hr/kradar.gif")
            }
            counter.decrementAndGet()
        })
        while (counter.get() != 0) {
            log("counter: $counter")
            Thread.sleep(300)
        }
    }

    private fun startFetch(url: String) {
        counter.incrementAndGet()
        start {
            try {
                fetchUrl(appContext, url, UP_TO_DATE)
            } catch (e: Exception) {
                fail(e.message)
            } finally {
                counter.decrementAndGet()
            }
        }
    }

    private fun log(message: String) {
        Log.i(logTag, message)
    }

    private fun logError(message: String) {
        Log.e(logTag, message)
    }
}
