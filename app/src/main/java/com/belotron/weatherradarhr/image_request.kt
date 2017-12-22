package com.belotron.weatherradarhr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.loopj.android.http.AsyncHttpClient
import com.loopj.android.http.AsyncHttpResponseHandler
import com.loopj.android.http.RequestParams
import cz.msebera.android.httpclient.Header
import cz.msebera.android.httpclient.message.BasicHeader
import java.io.File
import java.io.IOException
import java.lang.Long.parseLong
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.experimental.Continuation
import kotlin.coroutines.experimental.suspendCoroutine

fun ByteArray.toBitmap() : Bitmap =
        BitmapFactory.decodeByteArray(this, 0, this.size, BitmapFactory.Options())

private const val DEFAULT_LAST_MODIFIED = "Thu, 01 Jan 1970 00:00:00 GMT"
private const val FILENAME_SUBSTITUTE_CHAR = ":"
private const val HTTP_CACHE_DIR = "httpcache"
private val filenameCharsToAvoidRegex = Regex("""[\\|/$?*]""")
private val lastModifiedRegex = Regex("""\w{3}, \d{2} \w{3} \d{4} \d{2}:(\d{2}):(\d{2}) GMT""")
private val lastModifiedDateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
private val client : AsyncHttpClient = AsyncHttpClient()

suspend fun fetchImage(context: Context, url: String, onlyIfNew: Boolean): Pair<Long, ByteArray?> =
    suspendCoroutine { cont ->
        val headers = loadLastModified(context, url)?.let { arrayOf(BasicHeader("If-Modified-Since", it)) }
        client.get(context, url, headers, RequestParams(), ResponseHandler(context, cont, url, onlyIfNew))
    }

private class ResponseHandler(
        private val context: Context,
        private val cont: Continuation<Pair<Long, ByteArray?>>,
        private val url: String,
        private val onlyIfNew: Boolean
) : AsyncHttpResponseHandler() {
    override fun onSuccess(
            statusCode: Int,
            headers: Array<out Header>,
            responseBody: ByteArray
    ) = synchronized(client) {
        try {
            val lastModifiedStr = headers.find { it.name == "Last-Modified" }?.value ?: DEFAULT_LAST_MODIFIED
            val lastModified = lastModifiedStr.parseLastModified()
            MyLog.i("Last-Modified $lastModifiedStr: $url")
            val cachedIn = Try({ cachedDataIn(context, url) })
            val imgBytes: ByteArray = if (cachedIn == null) responseBody
            else {
                val cachedLastModified = Try({ cachedIn.readUTF().parseLastModified() })
                if (cachedLastModified == null || cachedLastModified < lastModified) {
                    cachedIn.close()
                    val cacheFile = cacheFile(context, url)
                    try {
                        dataOut(fileOut(cacheFile)).use { cachedOut ->
                            cachedOut.writeUTF(lastModifiedStr)
                            cachedOut.write(responseBody)
                        }
                    } catch (e: IOException) {
                        MyLog.e("Failed to write cached image to $cacheFile", e)
                    }
                    responseBody
                } else if (cachedLastModified == lastModified) {
                    cachedIn.close()
                    responseBody
                } else { // cachedLastModified > lastModified
                    cachedIn.use { it.readBytes() }
                }
            }
            val hourRelativeModTime = parseHourRelativeModTime(lastModifiedStr)
            try {
                cont.resume(Pair(hourRelativeModTime, imgBytes))
            } catch (t: Throwable) {
                MyLog.e("Continuation failed", t)
            }
        } catch (t : Throwable) {
            MyLog.e("Failed to handle a successful image response", t)
            cont.resumeWithException(t)
        }
    }

    override fun onFailure(statusCode: Int, headers: Array<out Header>?, responseBody: ByteArray?, error: Throwable) {
        when (statusCode) {
            304 -> try {
                if (onlyIfNew) {
                    cont.resume(Pair(0L, null))
                    return
                }
                val (lastModifiedStr, imgBytes) =
                        cachedDataIn(context, url).use { Pair(it.readUTF(), it.readBytes()) }
                MyLog.i("Not Modified since $lastModifiedStr: $url")
                cont.resume(Pair(parseHourRelativeModTime(lastModifiedStr), imgBytes))
            } catch (t : Throwable) {
                MyLog.e("Failed to handle 304 NOT MODIFIED", t)
                cont.resumeWithException(t)
            }
            else -> {
                MyLog.e("Failed to retrieve $url", error)
                val cachedImg = if (onlyIfNew) null
                else Try({ cachedDataIn(context, url).use { it.readUTF(); it.readBytes() } })
                cont.resumeWithException(ImageFetchException(error, cachedImg))
            }
        }
    }

    private fun String.parseLastModified() = lastModifiedDateFormat.parse(this).time

    private fun parseHourRelativeModTime(lastModifiedStr: String): Long {
        val groups = lastModifiedRegex.matchEntire(lastModifiedStr)?.groupValues
                ?: throw NumberFormatException("Failed to parse Last-Modified header: '$lastModifiedStr'")
        return 60 * parseLong(groups[1]) + parseLong(groups[2])
    }
}

class ImageFetchException(cause : Throwable, val cached : ByteArray?) : RuntimeException(cause)

private fun loadLastModified(context: Context, url: String) = Try({ cachedDataIn(context, url).use { it.readUTF() } })

private fun cachedDataIn(context: Context, url: String) = dataIn(fileIn(cacheFile(context, url)))

private fun cacheFile(context: Context, url: String): File {
    val fname = filenameCharsToAvoidRegex.replace(url, FILENAME_SUBSTITUTE_CHAR)
    val file = File(context.noBackupFilesDir, "$HTTP_CACHE_DIR/" + fname)
    file.mkdirs()
    return file
}

fun <T> Try(block: () -> T) = try {
    block()
} catch(t :Throwable) {
    null
}
