package com.belotron.weatherradarhr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.belotron.weatherradarhr.FetchPolicy.ONLY_IF_NEW
import com.belotron.weatherradarhr.FetchPolicy.PREFER_CACHED
import com.belotron.weatherradarhr.FetchPolicy.UP_TO_DATE
import com.belotron.weatherradarhr.gifdecode.ImgDecodeException
import com.belotron.weatherradarhr.gifdecode.ParsedGif
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.lang.Integer.parseInt
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.text.Charsets.UTF_8
import java.util.logging.Logger as JulLogger

private const val DEFAULT_LAST_MODIFIED = "Thu, 01 Jan 1970 00:00:00 GMT"
private const val FILENAME_SUBSTITUTE_CHAR = ":"
private const val HTTP_CACHE_DIR = "httpcache"
private const val ESTIMATED_CONTENT_LENGTH = 1 shl 15

private val filenameCharsToAvoidRegex = Regex("""[\\|/$?*]""")
private val lastModifiedRegex = Regex("""\w{3}, \d{2} \w{3} \d{4} \d{2}:(\d{2}):(\d{2}) GMT""")
private val lastModifiedDateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)

enum class FetchPolicy { UP_TO_DATE, PREFER_CACHED, ONLY_IF_NEW }

class ImageFetchException(val cached : Any?) : Exception()

suspend fun Context.fetchGif(url: String, fetchPolicy: FetchPolicy): Pair<Long, ParsedGif?> =
        fetchImg(url, fetchPolicy, ByteArray::parseGif)

suspend fun Context.fetchBitmap(url: String, fetchPolicy: FetchPolicy): Pair<Long, Bitmap?> =
        fetchImg(url, fetchPolicy) { BitmapFactory.decodeByteArray(it, 0, it.size) }

/**
 * The returned object may be `null` only with the [ONLY_IF_NEW] fetch
 * policy, if there is no new image.
 *
 * In the case of an error the function throws [ImageFetchException] which
 * holds a cached byte array, if available.
 */
private suspend fun <T> Context.fetchImg(
        url: String, fetchPolicy: FetchPolicy, decode: (ByteArray) -> T
): Pair<Long, T?> = withContext(threadPool) {
    Exchange(this@fetchImg, url, fetchPolicy, decode).proceed()
}

class Exchange<out T>(
        private val context: Context,
        private val url: String,
        private val fetchPolicy: FetchPolicy,
        private val decode: (ByteArray) -> T
) {
    suspend fun proceed(): Pair<Long, T?> {
        if (fetchPolicy == PREFER_CACHED) {
            try {
                loadCachedResult?.also { return it }
            } catch (e: Exception) {
                severe(e) { "Error loading cached image for $url" }
            }
        }
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            val ifModifiedSince = loadCachedLastModified(url)
            ifModifiedSince?.let { conn.addRequestProperty("If-Modified-Since", it) }
            conn.connect()
            return when {
                conn.responseCode == 200 ->
                    conn.handleSuccessResponse()
                conn.responseCode != 304 ->
                    throw HttpErrorResponse()
                fetchPolicy == ONLY_IF_NEW -> // responseCode == 304, but onlyIfNew is set so don't fetch from cache
                    Pair(0L, null)
                else -> { // responseCode == 304, fetch from cache
                    info { "Not Modified since $ifModifiedSince: $url" }
                    loadCachedResult ?: context.fetchImg(url, UP_TO_DATE, decode)
                }
            }
        } catch (t: Throwable) {
            if (t is HttpErrorResponse) {
                conn!!.logErrorResponse()
            } else {
                severe(t) { "Error fetching $url" }
            }
            throw ImageFetchException(
                    if (fetchPolicy == ONLY_IF_NEW) null
                    else runOrNull { loadCachedImage })
        } finally {
            conn?.disconnect()
        }
    }

    private fun HttpURLConnection.handleSuccessResponse(): Pair<Long, T> {
        val contentLength = getHeaderFieldInt("Content-Length", ESTIMATED_CONTENT_LENGTH)
        val lastModifiedStr = getHeaderField("Last-Modified") ?: DEFAULT_LAST_MODIFIED
        val fetchedLastModified = lastModifiedStr.parseLastModified()
        val responseBody = lazy { inputStream.use { it.readBytes() } }
        info { "Fetching content of length $contentLength, Last-Modified $lastModifiedStr: $url" }
        return try {
            val cachedIn = runOrNull { cachedDataIn(url.toExternalForm()) }
            val parsedGif = if (cachedIn == null) {
                fetchContentAndUpdateCache(responseBody, lastModifiedStr)
            } else {
                // These checks are repeated in updateCache(). While the response body is being
                // loaded, another thread could write a newer cached image.
                val cachedLastModified = runOrNull { cachedIn.readUTF().parseLastModified() }
                if (cachedLastModified == null || cachedLastModified < fetchedLastModified) {
                    cachedIn.close()
                    fetchContentAndUpdateCache(responseBody, lastModifiedStr)
                } else { // cachedLastModified >= fetchedLastModified, can happen with concurrent requests
                    inputStream.close()
                    cachedIn.use { it.readBytes() }.parseOrInvalidateImage
                }
            }
            Pair(parseLastModified_mmss(lastModifiedStr), parsedGif)
        } catch (t: Throwable) {
            severe(t) { "Failed to handle a successful image response for $url" }
            throw t
        }
    }

    private fun fetchContentAndUpdateCache(
            responseBody: Lazy<ByteArray>, lastModifiedStr: String
    ): T {
        val bytes = responseBody.value
        return decode(bytes).also {
            updateCache(context.cacheFile(url), lastModifiedStr, bytes)
        }
    }

    private val loadCachedResult: Pair<Long, T>? = runOrNull {
        val (lastModifiedStr, imgBytes) = cachedDataIn(url).use { Pair(it.readUTF(), it.readBytes()) }
        Pair(parseLastModified_mmss(lastModifiedStr), imgBytes.parseOrInvalidateImage)
    }

    private val loadCachedImage: T? = runOrNull {
        cachedDataIn(url).use { it.readUTF(); it.readBytes() }
    }?.parseOrInvalidateImage

    private val ByteArray.parseOrInvalidateImage: T
        get() {
            try {
                return decode(this)
            } catch (e: ImgDecodeException) {
                severe { "Image parsing error" }
                context.invalidateCache(url)
                throw e
            }
        }

    private fun loadCachedLastModified(url: String) = runOrNull { cachedDataIn(url).use { it.readUTF() } }

    private fun HttpURLConnection.logErrorResponse() {
        val responseBody = runOrNull { '\n' + String(inputStream.use { it.readBytes() }, UTF_8) } ?: ""
        severe { "Failed to retrieve $url: $responseCode$responseBody" }
    }

    private fun parseLastModified_mmss(lastModifiedStr: String): Long {
        val groups = lastModifiedRegex.matchEntire(lastModifiedStr)?.groupValues
                ?: throw NumberFormatException("Failed to parse Last-Modified header: '$lastModifiedStr'")
        return 60L * parseInt(groups[1]) + parseInt(groups[2])
    }

    private fun String.parseLastModified() = lastModifiedDateFormat.parse(this).time

    private fun cachedDataIn(url: String) = context.cacheFile(url).dataIn()

    private fun updateCache(cacheFile: File, lastModifiedStr: String, responseBody: ByteArray) {
        val growingFile = File(cacheFile.path + ".growing")
        synchronized(threadPool) {
            try {
                val cachedLastModified = runOrNull { growingFile.dataIn().use { it.readUTF() }.parseLastModified() }
                val fetchedLastModified = lastModifiedStr.parseLastModified()
                cachedLastModified?.takeIf { it >= fetchedLastModified }?.also {
                    return
                }
                growingFile.dataOut().use { it.writeUTF(lastModifiedStr); it.write(responseBody) }
                growingFile.renameTo(cacheFile).takeIf { !it }?.also {
                    throw IOException("Failed to rename $growingFile to ${cacheFile.name}")
                }
            } catch (e: IOException) {
                severe(e) { "Failed to write cached image to $growingFile" }
            }
        }
    }
}

fun Context.invalidateCache(url: String) {
    synchronized(threadPool) {
        severe { "Invalidating cache for $url" }
        val cacheFile = cacheFile(url)
        if (!cacheFile.delete()) {
            severe { "Failed to delete a broken cached image for $url" }
            // At least write a stale last-modified date to prevent retry loops
            cacheFile.dataOut().use { cachedOut ->
                cachedOut.writeUTF(DEFAULT_LAST_MODIFIED)
            }
        }
    }
}

private fun Context.cacheFile(url: String): File {
    val fname = filenameCharsToAvoidRegex.replace(url, FILENAME_SUBSTITUTE_CHAR)
    val file = fileInCache("$HTTP_CACHE_DIR/$fname")
    if (file.isDirectory) {
        file.delete()
    }
    file.parentFile?.mkdirs()
    return file
}

private class HttpErrorResponse : Exception()
