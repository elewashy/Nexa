package com.elewashy.nexa.feature.browser.presentation.webview

import android.webkit.WebResourceRequest
import com.elewashy.nexa.feature.downloads.domain.model.DownloadMetadata

/** Per-WebView bounded cache. WebView does not expose POST bodies or redirect request headers. */
class DownloadRequestCapture {
    private val requests = object : LinkedHashMap<String, Pair<Long, DownloadMetadata>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, DownloadMetadata>>?) = size > 64
    }

    @Synchronized
    fun record(request: WebResourceRequest) {
        requests[request.url.toString()] = System.nanoTime() to DownloadMetadata(
            headers = request.requestHeaders.toMap(), method = request.method,
        )
    }

    @Synchronized
    fun take(url: String): DownloadMetadata? = requests.remove(url)?.let { (time, metadata) ->
        metadata.takeIf { System.nanoTime() - time < 60_000_000_000 }
    }
}
