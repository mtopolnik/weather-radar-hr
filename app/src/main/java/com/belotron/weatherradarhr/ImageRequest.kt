package com.belotron.weatherradarhr

import android.content.Context
import com.loopj.android.http.AsyncHttpResponseHandler
import com.loopj.android.http.RequestParams
import cz.msebera.android.httpclient.Header
import cz.msebera.android.httpclient.message.BasicHeader
import java.util.concurrent.ConcurrentHashMap

const val DEFAULT_LAST_MODIFIED = "Thu, 1 Jan 1970 00:00:00 GMT"

object ImageRequest {
    private val urlToLastModified = ConcurrentHashMap<String, String>()

    private fun findLastModified(url: String) = urlToLastModified[url] ?: DEFAULT_LAST_MODIFIED

    fun sendImageRequest(context : Context,
                         url : String,
                         onSuccess : (ByteArray) -> Unit = {},
                         onCompletion : () -> Unit = {}
    ) {
        client.get(context, url, arrayOf(BasicHeader("If-Modified-Since", findLastModified(url))),
                RequestParams(), ResponseHandler(url, onSuccess, onCompletion))
    }

    class ResponseHandler(
            private val url : String,
            private val onSuccess: (ByteArray) -> Unit,
            private val onCompletion: () -> Unit
    ) : AsyncHttpResponseHandler() {
        override fun onSuccess(statusCode: Int, headers: Array<out Header>, responseBody: ByteArray?) {
            MyLog.i("""Modified since ${findLastModified(url)}""")
            headers.find { it.name == "Last-Modified" }?.apply {
                urlToLastModified[url] = value
            }
            onSuccess(responseBody!!)
            onCompletion()
        }

        override fun onFailure(statusCode: Int, headers: Array<out Header>, responseBody: ByteArray?, error: Throwable) {
            when (statusCode) {
                304 -> MyLog.i("""Not Modified since ${findLastModified(url)}""")
                else -> MyLog.e("Failed receiving image", error)
            }
            onCompletion()
        }
    }
}
