package kr.iamtalker.waterview

import android.webkit.MimeTypeMap
import java.io.Closeable
import java.io.InputStream

/**
 * Serving one open document to the WebView over HTTP.
 *
 * The viewer pages fetch the file they are showing from /doc/, which
 * ViewerActivity answers out of shouldInterceptRequest. That means writing
 * range responses by hand, so the parsing and the byte limiting live here
 * where they can be tested without a WebView.
 */

/**
 * Above this, a document is served in ranges rather than read whole.
 *
 * This is a memory threshold, not a speed one. Bulk and ranged loading were
 * measured against each other at 0.2, 2.7, 8, 16, 32 and 53 MB on a Nothing
 * Phone 2: below about 32 MB the difference had no consistent sign and stayed
 * inside run-to-run noise, and only at 53 MB did ranging win repeatably, by
 * around 80 ms. So anywhere in that band is equally defensible on speed, and
 * the number is chosen instead for what it avoids holding in memory. 16 MB is
 * comfortable to buffer on a low-end device; a 50 MB scan is not.
 */
internal const val RANGE_THRESHOLD_BYTES = 16L * 1024 * 1024

/**
 * Whether to serve this document in ranges. Decided in one place because both
 * the response headers and the page's choice of loader have to agree.
 */
internal fun useRanges(total: Long): Boolean = total >= RANGE_THRESHOLD_BYTES

/**
 * "bytes=start-end" resolved against a known total. Null means serve the whole
 * thing: an unparseable header, an unsatisfiable one, or a provider that would
 * not give us a length to range against.
 */
internal fun parseRange(header: String, total: Long): Pair<Long, Long>? {
    if (total <= 0) return null
    // Only the first range of a set; pdf.js never asks for more than one
    val spec = header.substringAfter("bytes=", "").substringBefore(',').trim()
    if (spec.isEmpty()) return null
    val start = spec.substringBefore('-').trim().toLongOrNull() ?: return null
    val end = spec.substringAfter('-').trim().toLongOrNull() ?: (total - 1)
    if (start < 0 || start > end || start >= total) return null
    return start to minOf(end, total - 1)
}

/** Content type for the document, from the extension rather than the provider. */
internal fun documentMime(ext: String): String = when (ext) {
    "svg" -> "image/svg+xml"
    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        ?: "application/octet-stream"
}

/** Stops at [remaining] bytes, and closes the descriptor along with the stream. */
internal class LimitedInputStream(
    private val source: InputStream,
    private var remaining: Long,
    private val alsoClose: Closeable
) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0) return -1
        return source.read().also { if (it >= 0) remaining-- }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = source.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }

    override fun available(): Int = minOf(source.available().toLong(), remaining).toInt()

    override fun close() {
        runCatching { source.close() }
        runCatching { alsoClose.close() }
    }
}
