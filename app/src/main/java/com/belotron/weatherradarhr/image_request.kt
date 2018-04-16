package com.belotron.weatherradarhr

import android.content.Context
import com.belotron.weatherradarhr.FetchPolicy.ONLY_IF_NEW
import com.belotron.weatherradarhr.FetchPolicy.PREFER_CACHED
import com.belotron.weatherradarhr.gifdecode.GifDecodeException
import com.belotron.weatherradarhr.gifdecode.GifParser
import com.belotron.weatherradarhr.gifdecode.ParsedGif
import kotlinx.coroutines.experimental.withContext
import java.io.File
import java.io.IOException
import java.lang.Long.parseLong
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.logging.Logger as JulLogger

private const val DEFAULT_LAST_MODIFIED = "Thu, 01 Jan 1970 00:00:00 GMT"
private const val FILENAME_SUBSTITUTE_CHAR = ":"
private const val HTTP_CACHE_DIR = "httpcache"
private val filenameCharsToAvoidRegex = Regex("""[\\|/$?*]""")
private val lastModifiedRegex = Regex("""\w{3}, \d{2} \w{3} \d{4} \d{2}:(\d{2}):(\d{2}) GMT""")
private val lastModifiedDateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)

enum class FetchPolicy { UP_TO_DATE, PREFER_CACHED, ONLY_IF_NEW }

class ImageFetchException(val cached : ParsedGif?) : Exception()

/**
 * The returned byte array may be `null` only with the [ONLY_IF_NEW] fetch
 * policy, if there is no new image.
 *
 * In the case of an error the function throws [ImageFetchException] which
 * holds a cached byte array, if available.
 */
suspend fun fetchUrl(
        context: Context, url: String, fetchPolicy: FetchPolicy
): Pair<Long, ParsedGif?> = withContext(threadPool) {
    if (fetchPolicy == PREFER_CACHED) {
        try {
            loadCachedResult(context, url)?.also {
                return@withContext it
            }
        } catch (e: Exception) {
            error(e) { "Error loading cached image for $url" }
        }
    }
    var conn: HttpURLConnection? = null
    try {
        conn = URL(url).openConnection() as HttpURLConnection
        val ifModifiedSince = loadCachedLastModified(context, url)
        ifModifiedSince?.let { conn.addRequestProperty("If-Modified-Since", it) }
        conn.connect()
        when {
            conn.responseCode == 200 ->
                handleSuccessResponse(conn, context)
            conn.responseCode != 304 ->
                throw HttpErrorResponse()
            fetchPolicy == ONLY_IF_NEW -> // responseCode == 304, but onlyIfNew is set so don't fetch from cache
                Pair(0L, null)
            else -> { // responseCode == 304, fetch from cache
                info { "Not Modified since $ifModifiedSince: $url" }
                loadCachedResult(context, url)!!
            }
        }
    } catch (t: Throwable) {
        if (t is HttpErrorResponse) {
            logErrorResponse(conn!!, url)
        } else {
            error(t) { "Error fetching $url" }
        }
        throw ImageFetchException(
                if (fetchPolicy == ONLY_IF_NEW) null
                else runOrNull { loadCachedImage(context, url) })
    } finally {
        conn?.disconnect()
    }
}

private fun handleSuccessResponse(conn: HttpURLConnection, context: Context): Pair<Long, ParsedGif> {
    val responseBody = lazy { conn.inputStream.use { it.readBytes() } }
    val lastModifiedStr = conn.getHeaderField("Last-Modified") ?: DEFAULT_LAST_MODIFIED
    val lastModified = lastModifiedStr.parseLastModified()
    val url = conn.url.toExternalForm()
    info { "Fetching content, Last-Modified $lastModifiedStr: $url" }
    return synchronized(threadPool) {
        try {
            val cachedIn = runOrNull { cachedDataIn(context, url) }
            val parsedGif = if (cachedIn == null) {
                fetchContentAndUpdateCache(responseBody, context, url, lastModifiedStr)
            }
            else {
                val cachedLastModified = runOrNull { cachedIn.readUTF().parseLastModified() }
                if (cachedLastModified == null || cachedLastModified < lastModified) {
                    cachedIn.close()
                    fetchContentAndUpdateCache(responseBody, context, url, lastModifiedStr)
                }
                else { // cachedLastModified >= lastModified, can happen with concurrent requests
                    conn.inputStream.close()
                    cachedIn.use { it.readBytes() }.parseGif(context, url)
                }
            }
            Pair(parseHourRelativeModTime(lastModifiedStr), parsedGif)
        } catch (t: Throwable) {
            error(t) { "Failed to handle a successful image response for $url" }
            throw t
        }
    }
}

private fun fetchContentAndUpdateCache(
        responseBody: Lazy<ByteArray>, context: Context, url: String, lastModifiedStr: String
): ParsedGif {
    val bytes = responseBody.value
    return GifParser.parse(bytes).also {
        updateCache(cacheFile(context, url), lastModifiedStr, bytes)
    }
}

fun Context.invalidateCache(url: String): Boolean {
    error { "Invalidating cache for $url" }
    return cacheFile(this, url).delete()
}

private fun updateCache(cacheFile: File, lastModifiedStr: String, responseBody: ByteArray) {
    try {
        cacheFile.dataOut().use { cachedOut ->
            cachedOut.writeUTF(lastModifiedStr)
            cachedOut.write(responseBody)
        }
    } catch (e: IOException) {
        error(e) {"Failed to write cached image to $cacheFile"}
    }
}

private fun loadCachedResult(context: Context, url: String): Pair<Long, ParsedGif>? = runOrNull {
    val (lastModifiedStr, imgBytes) = cachedDataIn(context, url).use { Pair(it.readUTF(), it.readBytes()) }
    return Pair(parseHourRelativeModTime(lastModifiedStr), imgBytes.parseGif(context, url))
}

private fun ByteArray.parseGif(context: Context, url: String): ParsedGif {
    try {
        return GifParser.parse(this)
    } catch (e: GifDecodeException) {
        error { "GIF parsing error" }
        if (!context.invalidateCache(url)) {
            error { "Failed to invalidate a broken cached image for $url"}
        }
        throw e
    }
}

private fun loadCachedLastModified(context: Context, url: String) = runOrNull {
    cachedDataIn(context, url).use { it.readUTF() }
}

private fun loadCachedImage(context: Context, url: String) = runOrNull {
    cachedDataIn(context, url).use { it.readUTF(); it.readBytes() }
}?.parseGif(context, url)

private fun logErrorResponse(conn: HttpURLConnection, url: String) {
    val responseBody = conn.inputStream.use { it.readBytes() }
    error("Failed to retrieve $url: ${conn.responseCode}\n${String(responseBody)}")
}

private fun parseHourRelativeModTime(lastModifiedStr: String): Long {
    val groups = lastModifiedRegex.matchEntire(lastModifiedStr)?.groupValues
            ?: throw NumberFormatException("Failed to parse Last-Modified header: '$lastModifiedStr'")
    return 60 * parseLong(groups[1]) + parseLong(groups[2])
}

private fun String.parseLastModified() = lastModifiedDateFormat.parse(this).time

private fun cachedDataIn(context: Context, url: String) = cacheFile(context, url).dataIn()

private fun cacheFile(context: Context, url: String): File {
    val fname = filenameCharsToAvoidRegex.replace(url, FILENAME_SUBSTITUTE_CHAR)
    val file = context.file("$HTTP_CACHE_DIR/$fname")
    if (file.isDirectory) {
        file.delete()
    }
    file.parentFile?.mkdirs()
    return file
}

private class HttpErrorResponse : Exception()
