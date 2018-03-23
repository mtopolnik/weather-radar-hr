package com.belotron.weatherradarhr

import android.content.Context
import com.belotron.weatherradarhr.FetchPolicy.ONLY_IF_NEW
import com.belotron.weatherradarhr.FetchPolicy.PREFER_CACHED
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

suspend fun fetchUrl(
        context: Context, url: String, fetchPolicy: FetchPolicy
): Pair<Long, ByteArray?> = withContext(threadPool) {
    if (fetchPolicy == PREFER_CACHED) {
        loadCachedResult(context, url)?.also {
            return@withContext it
        }
    }
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
        val ifModifiedSince = loadCachedLastModified(context, url)
        ifModifiedSince?.let { conn.addRequestProperty("If-Modified-Since", it) }
        conn.connect()
        when {
            conn.responseCode == 200 ->
                fetchContentAndUpdateCache(conn, context)
            conn.responseCode != 304 -> {
                logErrorResponse(conn, url)
                throw ImageFetchException(if (fetchPolicy == ONLY_IF_NEW) null else loadCachedImage(context, url))
            }
            fetchPolicy == ONLY_IF_NEW -> // responseCode == 304, but onlyIfNew is set so don't fetch from cache
                Pair(0L, null)
            else -> { // responseCode == 304, fetch from cache
                info { "Not Modified since $ifModifiedSince: $url" }
                loadCachedResult(context, url)!!
            }
        }
    } catch (e: Exception) {
        error(e) {"Error fetching $url"}
        throw ImageFetchException(if (fetchPolicy == ONLY_IF_NEW) null else loadCachedImage(context, url))
    } finally {
        conn.disconnect()
    }
}

private fun fetchContentAndUpdateCache(conn: HttpURLConnection, context: Context): Pair<Long, ByteArray> {
    val responseBody = lazy { conn.inputStream.use { it.readBytes() } }
    val lastModifiedStr = conn.getHeaderField("Last-Modified") ?: DEFAULT_LAST_MODIFIED
    val lastModified = lastModifiedStr.parseLastModified()
    val url = conn.url.toExternalForm()
    info { "Last-Modified $lastModifiedStr: $url" }
    return synchronized(threadPool) {
        try {
            val cachedIn = runOrNull { cachedDataIn(context, url) }
            val imgBytes = if (cachedIn == null) {
                updateCache(cacheFile(context, url), lastModifiedStr, responseBody.value)
                responseBody.value
            }
            else {
                val cachedLastModified = runOrNull { cachedIn.readUTF().parseLastModified() }
                if (cachedLastModified == null || cachedLastModified < lastModified) {
                    cachedIn.close()
                    updateCache(cacheFile(context, url), lastModifiedStr, responseBody.value)
                    responseBody.value
                }
                else { // cachedLastModified >= lastModified, can happen with concurrent requests
                    conn.inputStream.close()
                    cachedIn.use { it.readBytes() }
                }
            }
            Pair(parseHourRelativeModTime(lastModifiedStr), imgBytes)
        } catch (t: Throwable) {
            error(t) {"Failed to handle a successful image response"}
            throw t
        }
    }
}

fun invalidateCache(context: Context, url: String) {
    cacheFile(context, url).delete()
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

private fun loadCachedResult(context: Context, url: String): Pair<Long, ByteArray>? = runOrNull {
    val (lastModifiedStr, imgBytes) = cachedDataIn(context, url).use { Pair(it.readUTF(), it.readBytes()) }
    return Pair(parseHourRelativeModTime(lastModifiedStr), imgBytes)
}

private fun loadCachedLastModified(context: Context, url: String) = runOrNull {
    cachedDataIn(context, url).use { it.readUTF() }
}

private fun loadCachedImage(context: Context, url: String) = runOrNull {
    cachedDataIn(context, url).use { it.readUTF(); it.readBytes() }
}

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

class ImageFetchException(val cached : ByteArray?) : Exception()
