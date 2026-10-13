package com.elewashy.nexa.feature.downloads.domain.model

/** Request fields unavailable from WebView are left absent, never fabricated. */
data class DownloadMetadata(
    val headers: Map<String, String> = emptyMap(),
    val method: String = "GET",
    val contentDisposition: String? = null,
    val browserContentLength: Long = -1,
    val forceExtension: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val resolvedUrl: String? = null,
    val sourcePageUrl: String? = null,
    val mediaKey: String? = null,
    val imageIndex: Int? = null,
    val imageCount: Int? = null,
) {
    val validator: String?
        get() = etag?.takeIf { it.startsWith('"') && it.endsWith('"') } ?: lastModified
}
