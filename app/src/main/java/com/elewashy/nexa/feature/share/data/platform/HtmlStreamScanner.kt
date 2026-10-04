package com.elewashy.nexa.feature.share.data.platform

import okio.BufferedSource
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import org.json.JSONException
import org.json.JSONTokener

/**
 * Streaming extraction of embedded data from large HTML pages.
 *
 * Post pages are close to 1 MB, but the data an extractor needs is one
 * script block somewhere in the middle. Reading the whole body into a
 * `String` and running regexes over it costs several MB of transient
 * allocations per extraction and downloads bytes nobody reads. These helpers
 * scan the response stream instead: bytes before a match are discarded as
 * they are scanned (memory stays at a few segments plus the matched block),
 * and callers close the response as soon as they have what they need, so
 * the rest of the page is never downloaded.
 */
internal object HtmlStreamScanner {

    /** Upper bound for one captured block; real post data blocks are well under 200 KB. */
    const val MAX_BLOCK_BYTES = 2L * 1024 * 1024

    private val JSON_SCRIPT_OPEN = "<script type=\"application/json\"".encodeUtf8()
    private val SCRIPT_CLOSE = "</script>".encodeUtf8()
    private const val TAG_END = '>'.code.toByte()
    private const val QUOTE = '"'.code.toByte()
    private const val BACKSLASH = '\\'.code.toByte()

    /**
     * Visits the bodies of `<script type="application/json">` blocks that
     * contain [needle], in document order, until [transform] returns a value.
     * Blocks without the needle are skipped without being decoded.
     */
    fun <T : Any> firstJsonScript(source: BufferedSource, needle: String, transform: (String) -> T?): T? {
        val needleBytes = needle.encodeUtf8()
        while (source.seek(JSON_SCRIPT_OPEN)) {
            val tagEnd = source.indexOf(TAG_END, 0L, MAX_BLOCK_BYTES)
            if (tagEnd < 0L) return null
            source.skip(tagEnd + 1)

            val end = source.indexOf(SCRIPT_CLOSE, 0L, MAX_BLOCK_BYTES)
            if (end < 0L) return null
            if (source.buffer.indexOf(needleBytes, 0L, end) >= 0L) {
                transform(source.readUtf8(end))?.let { return it }
            } else {
                source.skip(end)
            }
            source.skip(SCRIPT_CLOSE.size.toLong())
        }
        return null
    }

    /**
     * Decodes the JSON string literal that follows the first occurrence of
     * [marker] (which must end right before the literal's opening quote),
     * e.g. `"contextJSON":` in `"contextJSON":"{\"a\":1}"` yields `{"a":1}`.
     */
    fun jsonStringAfter(source: BufferedSource, marker: String): String? {
        val markerBytes = marker.encodeUtf8()
        if (!source.seek(markerBytes)) return null
        source.skip(markerBytes.size.toLong())
        if (!source.request(1) || source.buffer[0] != QUOTE) return null

        // Find the closing quote: the first one not escaped by an odd run of backslashes.
        var from = 1L
        while (true) {
            val quote = source.indexOf(QUOTE, from, MAX_BLOCK_BYTES)
            if (quote < 0L) return null
            var backslashes = 0
            while (source.buffer[quote - 1 - backslashes] == BACKSLASH) backslashes++
            if (backslashes % 2 == 0) {
                val literal = source.readUtf8(quote + 1)
                return try {
                    JSONTokener(literal).nextValue() as? String
                } catch (_: JSONException) {
                    null
                }
            }
            from = quote + 1
        }
    }

    /**
     * Advances to the start of the next [pattern], discarding scanned bytes
     * so memory stays bounded however far the match is. Returns false (with
     * the source exhausted) when there is no further match.
     */
    private fun BufferedSource.seek(pattern: ByteString): Boolean {
        val overlap = pattern.size - 1L
        while (true) {
            val index = buffer.indexOf(pattern)
            if (index >= 0L) {
                skip(index)
                return true
            }
            // Keep a pattern-sized tail: a match may straddle the next read.
            if (buffer.size > overlap) skip(buffer.size - overlap)
            if (!request(buffer.size + 1)) return false
        }
    }
}
