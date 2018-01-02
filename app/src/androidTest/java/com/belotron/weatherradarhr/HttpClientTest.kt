package com.belotron.weatherradarhr

import android.os.Handler
import android.support.test.InstrumentationRegistry
import android.support.test.runner.AndroidJUnit4
import android.util.Log
import kotlinx.coroutines.experimental.Unconfined
import kotlinx.coroutines.experimental.launch
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Instrumentation test, which will execute on an Android device.
 *
 * @see [Testing documentation](http://d.android.com/tools/testing)
 */
@RunWith(AndroidJUnit4::class)
class HttpClientTest {

    private val logTag = "wrInstrumentedTest"
    private val appContext = InstrumentationRegistry.getTargetContext()
    private val counter = AtomicInteger()

    @Test
    fun testImageRequest() {
        counter.incrementAndGet()
        Handler(appContext.mainLooper).post( {
            (1..5).forEach {
                fetch("http://www.arso.gov.si/vreme/napovedi%20in%20podatki/radar.gif")
                fetch("http://vrijeme.hr/kradar.gif")
            }
            counter.decrementAndGet()
        })
        while (counter.get() != 0) {
            log("counter: $counter")
            Thread.sleep(300)
        }
    }

    private fun fetch(url: String) {
        counter.incrementAndGet()
        launch(Unconfined) {
            try {
                fetchImage(appContext, url, onlyIfNew = false)
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
