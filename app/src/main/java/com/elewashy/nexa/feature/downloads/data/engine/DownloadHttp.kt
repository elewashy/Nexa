package com.elewashy.nexa.feature.downloads.data.engine

import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import java.io.IOException

internal data class ContentRange(val start: Long, val end: Long, val total: Long) {
    companion object {
        private val pattern = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)", RegexOption.IGNORE_CASE)
        fun parse(value: String?): ContentRange? {
            val match = value?.let(pattern::matchEntire) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val total = match.groupValues[3].toLongOrNull() ?: return null
            return if (start <= end && end < total) ContentRange(start, end, total) else null
        }
    }
}

internal object DownloadHttp {
    fun request(item: DownloadItem, start: Long? = null, end: Long? = null): Request {
        val builder = Request.Builder().url(item.metadata.resolvedUrl ?: item.url)
            .tag(okhttp3.HttpUrl::class.java, item.url.toHttpUrl())
        require(item.metadata.method == "GET") { "WebView request body is unavailable; refusing to replay ${item.metadata.method} as GET" }
        item.metadata.headers.forEach { (name, value) -> builder.addHeader(name, value) }
        fun fallback(name: String, value: String?) {
            if (value != null && item.metadata.headers.keys.none { it.equals(name, true) }) builder.header(name, value)
        }
        fallback("User-Agent", item.userAgent)
        fallback("Referer", item.referer)
        fallback("Origin", item.origin)
        fallback("Cookie", item.cookies)
        // Range positions refer to the identity representation, not a compressed wire body.
        val source = item.url.toHttpUrl()
        val target = (item.metadata.resolvedUrl ?: item.url).toHttpUrl()
        if (source.scheme != target.scheme || source.host != target.host || source.port != target.port) {
            builder.removeHeader("Authorization").removeHeader("Cookie").removeHeader("Proxy-Authorization")
        }
        builder.header("Accept-Encoding", "identity")
        builder.removeHeader("Range").removeHeader("If-Range")
            .removeHeader("If-None-Match").removeHeader("If-Modified-Since")
            .removeHeader("If-Match").removeHeader("If-Unmodified-Since")
            .removeHeader("Host").removeHeader("Connection").removeHeader("Content-Length")
            .removeHeader("Transfer-Encoding")
        if (start != null) {
            builder.header("Range", "bytes=$start-${end ?: ""}")
            item.metadata.validator?.let { builder.header("If-Range", it) }
        }
        return builder.get().build()
    }

    fun validate(response: Response, start: Long, end: Long, total: Long, validator: String?) {
        if (response.code == 200 || response.code == 416) throw UnsafeResumeException("Server rejected the saved representation/range")
        checkStatus(response)
        if (response.code != 206) throw UnsafeResumeException("Expected 206, got ${response.code}")
        val range = ContentRange.parse(response.header("Content-Range"))
            ?: throw UnsafeResumeException("Invalid Content-Range")
        if (range.start != start || range.end != end || range.total != total) throw UnsafeResumeException("Content-Range does not match requested bytes")
        val length = response.body.contentLength()
        if (length >= 0 && length != end - start + 1) throw UnsafeResumeException("Partial response length mismatch")
        checkEncoding(response)
        val returned = if (validator?.startsWith('"') == true) response.header("ETag") else response.header("Last-Modified")
        if (returned != null && returned != validator) throw UnsafeResumeException("Resource validator changed")
    }

    val redirectCredentials = okhttp3.Interceptor { chain ->
        val request = chain.request()
        val original = request.tag(okhttp3.HttpUrl::class.java)
        val crossOrigin = original != null && (original.scheme != request.url.scheme ||
            original.host != request.url.host || original.port != request.url.port)
        chain.proceed(if (crossOrigin) request.newBuilder().removeHeader("Cookie")
            .removeHeader("Authorization").removeHeader("Proxy-Authorization").build() else request)
    }

    fun strongLastModified(response: Response): String? {
        val modified = response.header("Last-Modified") ?: return null
        val date = response.header("Date") ?: return null
        return runCatching {
            val format = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            val age = java.time.ZonedDateTime.parse(date, format).toEpochSecond() -
                java.time.ZonedDateTime.parse(modified, format).toEpochSecond()
            modified.takeIf { age >= 60 }
        }.getOrNull()
    }

    fun checkStatus(response: Response) {
        if (!response.isSuccessful) throw HttpDownloadException(response.code, response.header("Retry-After"))
    }

    fun checkEncoding(response: Response) {
        if (response.header("Content-Encoding")?.let { !it.equals("identity", true) } == true) {
            throw UnsafeResumeException("Server ignored identity encoding")
        }
    }
}

internal class UnsafeResumeException(message: String) : IOException(message)
internal class HttpDownloadException(val code: Int, val retryAfter: String?) : IOException("HTTP $code") {
    val retryable get() = code == 408 || code == 429 || code in 500..599
}
