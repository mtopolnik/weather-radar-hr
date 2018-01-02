package com.belotron.weatherradarhr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper.myLooper
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Long.parseLong
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.experimental.suspendCoroutine
import java.util.logging.Logger as JulLogger

fun ByteArray.toBitmap() : Bitmap =
        BitmapFactory.decodeByteArray(this, 0, this.size, BitmapFactory.Options())

private const val DEFAULT_LAST_MODIFIED = "Thu, 01 Jan 1970 00:00:00 GMT"
private const val FILENAME_SUBSTITUTE_CHAR = ":"
private const val HTTP_CACHE_DIR = "httpcache"
private val filenameCharsToAvoidRegex = Regex("""[\\|/$?*]""")
private val lastModifiedRegex = Regex("""\w{3}, \d{2} \w{3} \d{4} \d{2}:(\d{2}):(\d{2}) GMT""")
private val lastModifiedDateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
private val myClient = HttpClient()

fun Context.file(name: String) = File(noBackupFilesDir, name)

fun File.dataIn() = DataInputStream(FileInputStream(this))

fun File.dataOut() = DataOutputStream(FileOutputStream(this))

suspend fun fetchImage(context: Context, url: String, onlyIfNew: Boolean): Pair<Long, ByteArray?> =
    myClient.get(context, url, onlyIfNew)

class HttpClient {
    private val threadPool = Executors.newCachedThreadPool()

    /**
     * returns `Pair<lastModifiedModuloHour, responseBody>`
     */
    suspend fun get(context: Context, url: String, onlyIfNew: Boolean): Pair<Long, ByteArray?>
            = threadPool.submitAndSuspend {
        val conn = URL(url).openConnection() as HttpURLConnection
        val ifModifiedSince = loadLastModified(context, url)
        ifModifiedSince?.let { conn.addRequestProperty("If-Modified-Since", it) }
        conn.connect()
        try {
            when {
                conn.responseCode == 200 ->
                    fetchContentAndUpdateCache(conn, context)
                conn.responseCode != 304 -> {
                    logErrorResponse(conn, url)
                    throw ImageFetchException(if (onlyIfNew) null else fetchCached(context, url))
                }
                onlyIfNew -> // responseCode == 304, but onlyIfNew is set so don't fetch from cache
                    Pair(0L, null)
                else -> { // responseCode == 304, fetch from cache
                    MyLog.i("Not Modified since $ifModifiedSince: $url")
                    val (lastModifiedStr, imgBytes) =
                            cachedDataIn(context, url).use { Pair(it.readUTF(), it.readBytes()) }
                    Pair(parseHourRelativeModTime(lastModifiedStr), imgBytes)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchContentAndUpdateCache(conn: HttpURLConnection, context: Context): Pair<Long, ByteArray> {
        val url = conn.url.toExternalForm()
        val responseBody = conn.inputStream.use { it.readBytes() }
        val lastModifiedStr = conn.getHeaderField("Last-Modified") ?: DEFAULT_LAST_MODIFIED
        val lastModified = lastModifiedStr.parseLastModified()
        MyLog.i("Last-Modified $lastModifiedStr: $url")
        return synchronized(this) {
            try {
                val cachedIn = runOrNull({ cachedDataIn(context, url) })
                val imgBytes = if (cachedIn == null)
                    responseBody
                else {
                    val cachedLastModified = runOrNull({ cachedIn.readUTF().parseLastModified() })
                    when {
                        cachedLastModified == null || cachedLastModified < lastModified -> {
                            cachedIn.close()
                            updateCache(cacheFile(context, url), lastModifiedStr, responseBody)
                            responseBody
                        }
                        cachedLastModified == lastModified -> {
                            cachedIn.close()
                            responseBody
                        }
                        else -> // cachedLastModified > lastModified
                            cachedIn.use { it.readBytes() }
                    }
                }
                Pair(parseHourRelativeModTime(lastModifiedStr), imgBytes)
            } catch (t: Throwable) {
                MyLog.e("Failed to handle a successful image response", t)
                throw t
            }
        }
    }

    private fun logErrorResponse(conn: HttpURLConnection, url: String) {
        val responseBody = conn.inputStream.use { it.readBytes() }
        MyLog.e("Failed to retrieve $url: ${conn.responseCode}\n${String(responseBody)}")
    }

    private fun updateCache(cacheFile: File, lastModifiedStr: String, responseBody: ByteArray) = try {
        cacheFile.dataOut().use { cachedOut ->
            cachedOut.writeUTF(lastModifiedStr)
            cachedOut.write(responseBody)
        }
    } catch (e: IOException) {
        MyLog.e("Failed to write cached image to $cacheFile", e)
    }

    private fun fetchCached(context: Context, url: String) = runOrNull {
        cachedDataIn(context, url).use { it.readUTF(); it.readBytes() }
    }

}

private fun parseHourRelativeModTime(lastModifiedStr: String): Long {
    val groups = lastModifiedRegex.matchEntire(lastModifiedStr)?.groupValues
            ?: throw NumberFormatException("Failed to parse Last-Modified header: '$lastModifiedStr'")
    return 60 * parseLong(groups[1]) + parseLong(groups[2])
}

private fun String.parseLastModified() = lastModifiedDateFormat.parse(this).time

private fun loadLastModified(context: Context, url: String) = runOrNull({
    cachedDataIn(context, url).use { it.readUTF() }
})

private fun cachedDataIn(context: Context, url: String) = cacheFile(context, url).dataIn()

private fun cacheFile(context: Context, url: String): File {
    val fname = filenameCharsToAvoidRegex.replace(url, FILENAME_SUBSTITUTE_CHAR)
    val file = context.file("$HTTP_CACHE_DIR/$fname")
    file.mkdirs()
    return file
}

private suspend fun <T> ExecutorService.submitAndSuspend(block: () -> T) = suspendCoroutine<T> { cont ->
    val handler = myLooper()?.let { Handler(it) }
    submit {
        try {
            val result = block()
            resume(handler) { cont.resume(result) }
        } catch (e: Throwable) {
            resume(handler) { cont.resumeWithException(e) }
        }
    }
}

private fun resume(handler: Handler?, r: () -> Unit) {
    handler?.apply { post(r) } ?: r()
}

private inline fun <T> runOrNull(block: () -> T) = try {
    block()
} catch (t: Throwable) {
    null
}

class ImageFetchException(val cached : ByteArray?) : RuntimeException()
