package com.belotron.weatherradarhr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.belotron.weatherradarhr.CcOption.CC_PRIVATE
import com.belotron.weatherradarhr.FetchPolicy.*
import com.belotron.weatherradarhr.gifdecode.GifParser
import com.belotron.weatherradarhr.gifdecode.GifSequence
import com.belotron.weatherradarhr.gifdecode.ImgDecodeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.lang.Integer.parseInt
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.text.Charsets.UTF_8

private const val DEFAULT_LAST_MODIFIED_STR = "Thu, 01 Jan 1970 00:00:00 GMT"
private const val FILENAME_SUBSTITUTE_CHAR = ":"
private const val HTTP_CACHE_DIR = "httpcache"
private const val ESTIMATED_CONTENT_LENGTH = 1 shl 15

val CACHE_LOCK = Object()
private val filenameCharsToAvoidRegex = Regex("""[\\|/$?*]""")
private val lastModifiedRegex = Regex("""\w{3}, \d{2} \w{3} \d{4} \d{2}:(\d{2}):(\d{2}) GMT""")
private val lastModifiedDateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
private val defaultLastModified = lastModifiedDateFormat.parse(DEFAULT_LAST_MODIFIED_STR)!!.time
private val LimitedIO = IO.limitedParallelism(4)

enum class FetchPolicy { UP_TO_DATE, PREFER_CACHED, ONLY_IF_NEW, ONLY_CACHED }

class ImageFetchException(val cached : Any?) : Exception()

suspend fun fetchPngFrame(context: Context, url: String, fetchPolicy: FetchPolicy): Pair<Long, PngFrame?> =
    context.fetchImg(url, fetchPolicy, ::PngFrame)

suspend fun fetchGifSequence(context: Context, url: String, fetchPolicy: FetchPolicy): Pair<Long, GifSequence?> =
    context.fetchImg(url, fetchPolicy, GifParser::parse)

suspend fun fetchBitmap(context: Context, url: String, fetchPolicy: FetchPolicy): Pair<Long, Bitmap?> =
        context.fetchImg(url, fetchPolicy) { BitmapFactory.decodeByteArray(it, 0, it.size) }

fun fetchPngFromCache(context: Context, url: String): Pair<Long, PngFrame?> =
    Exchange(context, GlobalScope, url, ONLY_CACHED, ::PngFrame).proceed()


/**
 * The returned object may be `null` only with the [ONLY_IF_NEW] fetch
 * policy, if there is no new image.
 *
 * In the case of an error the function throws [ImageFetchException] which
 * holds a cached byte array, if available.
 */
private suspend fun <T> Context.fetchImg(
        url: String, fetchPolicy: FetchPolicy, decode: (ByteArray) -> T
): Pair<Long, T?> {
    val context = this
    return coroutineScope {
        val exchange = Exchange(context, this, url, fetchPolicy, decode)
        val doneSignal = CompletableDeferred<Unit>()
        launch {
            try {
                doneSignal.await()
            } catch (e: CancellationException) {
                val inputStream = exchange.inputStream ?: return@launch
                info { "Request cancelled, closing connection to $url" }
                withContext(NonCancellable + IO) {
                    try {
                        inputStream.close()
                    } catch (e: IOException) { }
                }
            }
        }
        withContext(LimitedIO) {
            exchange.proceed()
        }.also {
            doneSignal.complete(Unit)
        }
    }
}

class Exchange<out T>(
        private val context: Context,
        private val coroScope: CoroutineScope,
        private val url: String,
        private val fetchPolicy: FetchPolicy,
        private val decode: (ByteArray) -> T
) {
    @Volatile
    var inputStream: InputStream? = null

    fun proceed(): Pair<Long, T?> {
        if (fetchPolicy == PREFER_CACHED) {
            try {
                loadCachedResult()?.also { return it }
            } catch (e: Exception) {
                severe(CC_PRIVATE, e) { "Error loading cached image for $url" }
            }
        }
        if (fetchPolicy == ONLY_CACHED) {
            loadCachedResult()?.also { return it }
            return Pair(0L, null)
        }
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            val ifModifiedSince = loadCachedLastModified(url)
            ifModifiedSince?.let { conn.addRequestProperty("If-Modified-Since", it) }
            conn.connect()
            if (!coroScope.isActive) {
                throw CancellationException()
            }
            return when {
                conn.responseCode == 200 -> {
                    conn.handleSuccessResponse()
                }
                conn.responseCode != 304 ->
                    throw HttpErrorResponse()
                fetchPolicy == ONLY_IF_NEW -> // responseCode == 304, but onlyIfNew is set so don't fetch from cache
                    Pair(0L, null)
                else -> { // responseCode == 304, fetch from cache
                    info { "Not Modified since $ifModifiedSince: $url" }
                    loadCachedResult() ?: Exchange(context, coroScope, url, UP_TO_DATE, decode).proceed()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is HttpErrorResponse) {
                conn!!.logErrorResponse()
            } else {
                severe(CC_PRIVATE, e) { "Error fetching $url" }
            }
            throw ImageFetchException(
                    if (fetchPolicy == ONLY_IF_NEW) null
                    else runOrNull { loadCachedImage() })
        } finally {
            conn?.disconnect()
        }
    }

    private fun HttpURLConnection.handleSuccessResponse(): Pair<Long, T> {
        val contentLength = getHeaderFieldInt("Content-Length", ESTIMATED_CONTENT_LENGTH)
        val lastModifiedStr = getHeaderField("Last-Modified") ?: DEFAULT_LAST_MODIFIED_STR
        val fetchedLastModified = lastModifiedStr.parseLastModified()
        val responseBody = lazy {
            this@Exchange.inputStream = inputStream
            inputStream.use { it.readBytes() }
                .also {
                    this@Exchange.inputStream = null
                    if (!coroScope.isActive) throw CancellationException()
                }
        }
        info { "Fetching content of length $contentLength, Last-Modified $lastModifiedStr: $url" }
        return try {
            val cachedIn = runOrNull { context.cachedDataIn(url.toExternalForm()) }
            val decodedImage = if (cachedIn == null) {
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
            Pair(parseLastModified_mmss(lastModifiedStr), decodedImage)
        } catch (e: Exception) {
            severe(CC_PRIVATE) { "Failed to handle a successful image response for $url" }
            throw e
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

    private fun loadCachedResult(): Pair<Long, T>? = runOrNull {
        val (lastModifiedStr, imgBytes) = context.cachedDataIn(url).use { Pair(it.readUTF(), it.readBytes()) }
        Pair(parseLastModified_mmss(lastModifiedStr), imgBytes.parseOrInvalidateImage)
    }

    private fun loadCachedImage(): T? = runOrNull {
        context.cachedDataIn(url).use { it.readUTF(); it.readBytes() }
    }?.parseOrInvalidateImage

    private val ByteArray.parseOrInvalidateImage: T
        get() {
            try {
                return decode(this)
            } catch (e: ImgDecodeException) {
                severe(CC_PRIVATE) { "Image parsing error" }
                context.invalidateCache(url)
                throw e
            }
        }

    private fun loadCachedLastModified(url: String) = runOrNull { context.cachedDataIn(url).use { it.readUTF() } }

    private fun HttpURLConnection.logErrorResponse() {
        val responseBody = runOrNull { '\n' + String(inputStream.use { it.readBytes() }, UTF_8) } ?: ""
        severe(CC_PRIVATE) { "Failed to retrieve $url: $responseCode$responseBody" }
    }

    private fun parseLastModified_mmss(lastModifiedStr: String): Long {
        val groups = lastModifiedRegex.matchEntire(lastModifiedStr)?.groupValues
                ?: throw NumberFormatException("Failed to parse Last-Modified header: '$lastModifiedStr'")
        return 60L * parseInt(groups[1]) + parseInt(groups[2])
    }

    private fun String.parseLastModified() = lastModifiedDateFormat.parse(this)?.time ?: defaultLastModified

    private fun updateCache(cacheFile: File, lastModifiedStr: String, responseBody: ByteArray) {
        val growingFile = File(cacheFile.path + ".growing")
        synchronized(CACHE_LOCK) {
            try {
                val cachedLastModified = runOrNull { cacheFile.dataIn().use { it.readUTF() }.parseLastModified() }
                val fetchedLastModified = lastModifiedStr.parseLastModified()
                cachedLastModified?.takeIf { it >= fetchedLastModified }?.also {
                    return
                }
                growingFile.dataOut().use { it.writeUTF(lastModifiedStr); it.write(responseBody) }
                growingFile.renameTo(cacheFile).takeIf { !it }?.also {
                    throw IOException("Failed to rename $growingFile to ${cacheFile.name}")
                }
            } catch (e: IOException) {
                severe(CC_PRIVATE, e) { "Failed to write cached image to $growingFile" }
            }
        }
    }
}

private fun Context.cachedDataIn(url: String) = cacheFile(url).dataIn()

fun Context.invalidateCache(url: String) {
    synchronized(CACHE_LOCK) {
        severe(CC_PRIVATE) { "Invalidating cache for $url" }
        val cacheFile = cacheFile(url)
        if (!cacheFile.delete()) {
            severe(CC_PRIVATE) { "Failed to delete a broken cached image for $url" }
            // At least write a stale last-modified date to prevent retry loops
            cacheFile.dataOut().use { cachedOut ->
                cachedOut.writeUTF(DEFAULT_LAST_MODIFIED_STR)
            }
        }
    }
}

fun Context.deleteCached(url: String) {
    synchronized(CACHE_LOCK) {
        val cacheFile = cacheFile(url)
        if (!cacheFile.delete()) {
            throw IOException("Failed to delete $cacheFile")
        }
    }
}

fun Context.renameCached(urlNow: String, urlToBe: String) {
    synchronized(CACHE_LOCK) {
        val cacheFileNow = cacheFile(urlNow)
        val cacheFileToBe = cacheFile(urlToBe)
        if (!cacheFileNow.renameTo(cacheFileToBe)) {
            severe(CC_PRIVATE) { "Failed to rename $cacheFileNow to $cacheFileToBe" }
        }
    }
}

fun Context.copyCached(fromUrl: String, toUrl: String) {
    synchronized(CACHE_LOCK) {
        cachedDataIn(fromUrl).use { inputStream ->
            cacheFile(toUrl).dataOut().use { outputStream ->
                inputStream.copyTo(outputStream)
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
