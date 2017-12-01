package com.belotron.weatherradarhr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.loopj.android.http.AsyncHttpClient
import com.loopj.android.http.AsyncHttpResponseHandler
import com.loopj.android.http.RequestParams
import cz.msebera.android.httpclient.Header
import cz.msebera.android.httpclient.message.BasicHeader
import java.lang.Long.parseLong
import java.util.concurrent.ConcurrentHashMap

fun ByteArray.toBitmap() : Bitmap =
        BitmapFactory.decodeByteArray(this, 0, this.size, BitmapFactory.Options())

object ImageRequest {
    private const val DEFAULT_LAST_MODIFIED = "Thu, 01 Jan 1970 00:00:00 GMT"
    private val lastModifiedRegex = Regex("""\w{3}, \d{2} \w{3} \d{4} \d{2}:(\d{2}):(\d{2}) GMT""")
    private val client : AsyncHttpClient = AsyncHttpClient()
    private val urlToLastModified = ConcurrentHashMap<String, String>()

    private fun findLastModified(url: String) = urlToLastModified[url] ?: DEFAULT_LAST_MODIFIED

    fun sendImageRequest(context : Context,
                         url : String,
                         useIfModifiedSince : Boolean = true,
                         // last modified time, seconds past full hour
                         onSuccess : (ByteArray, Long) -> Unit = {_, _ -> },
                         onNotModified : () -> Unit = {},
                         onFailure : () -> Unit = {},
                         onCompletion : () -> Unit = {}
    ) {
        val headers =
                if (useIfModifiedSince) arrayOf(BasicHeader("If-Modified-Since", findLastModified(url)))
                else arrayOf()
        client.get(context, url, headers, RequestParams(), ResponseHandler(url,
                onSuccess = onSuccess, onFailure = onFailure,
                onNotModified = onNotModified, onCompletion = onCompletion))
    }

    private fun parseHourRelativeModTime(lastModifiedStr: String): Long {
        val groups = lastModifiedRegex.matchEntire(lastModifiedStr)?.groupValues
                ?: throw NumberFormatException("""Failed to parse Last-Modified header: "$lastModifiedStr"""")
        return 60 * parseLong(groups[1]) + parseLong(groups[2])
    }

    private class ResponseHandler(
            private val url: String,
            // last modified time, seconds past full hour
            private val onSuccess: (ByteArray, Long) -> Unit,
            private val onFailure: () -> Unit,
            private val onNotModified: () -> Unit,
            private val onCompletion: () -> Unit
    ) : AsyncHttpResponseHandler() {
        override fun onSuccess(statusCode: Int, headers: Array<out Header>, responseBody: ByteArray?) {
            val lastModified = headers.find { it.name == "Last-Modified" }?.value ?: DEFAULT_LAST_MODIFIED
            MyLog.i("""Last-Modified $lastModified: $url""")
            urlToLastModified[url] = lastModified
            try {
                val hourRelativeModTime = parseHourRelativeModTime(lastModified)
                onSuccess(responseBody!!, hourRelativeModTime)
            } catch (t : Throwable) {
                MyLog.e("""Failed to handle a successful image response""", t)
                onFailure()
            } finally {
                onCompletion()
            }
        }

        override fun onFailure(
                statusCode: Int, headers: Array<out Header>?, responseBody: ByteArray?, error: Throwable
        ) {
            when (statusCode) {
                304 -> {
                    MyLog.i("""Not Modified since ${findLastModified(url)}: $url""")
                    onNotModified()
                }
                else -> {
                    MyLog.e("""Failed to retrieve $url""", error)
                    onFailure()
                }
            }
            onCompletion()
        }
    }
}
