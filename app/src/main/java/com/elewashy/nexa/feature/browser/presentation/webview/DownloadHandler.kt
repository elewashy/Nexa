package com.elewashy.nexa.feature.browser.presentation.webview

import android.content.Context
import android.webkit.URLUtil
import android.webkit.CookieManager
import com.elewashy.nexa.ui.components.common.AppMessages
import androidx.core.net.toUri
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.downloads.presentation.service.DownloadService

/**
 * DownloadHandler — Centralizes download-start logic.
 *
 * Converts a URL + metadata into a [DownloadService] intent and starts the
 * service.  All callers (WebView download listener, auto-download trigger)
 * go through the same path, eliminating duplication.
 */
data class StartedDownload(
    val url: String,
    val startedAt: Long,
)

object DownloadHandler {

    /** Source tag for normal browser downloads. */
    const val SOURCE_BROWSER = "BROWSER"

    /**
     * Starts a download via [DownloadService].
     *
     * @param context          Application / Activity context (non-null)
     * @param url              Raw download URL
     * @param mimeType         MIME type reported by the server (nullable)
     * @param contentDisposition Content-Disposition header value (nullable)
     * @param userAgent        WebView user-agent string
     * @param currentPageUrl   URL of the page the WebView is currently showing
     * @param source           Source tag for the download.
     * @param onDownloadStarted Optional UI callback for Compose callers.
     */
    fun startDownload(
        context: Context,
        url: String,
        mimeType: String?,
        contentDisposition: String? = null,
        userAgent: String,
        currentPageUrl: String?,
        cookieManager: CookieManager = CookieManager.getInstance(),
        source: String = SOURCE_BROWSER,
        onDownloadStarted: ((StartedDownload) -> Unit)? = null,
    ) {
        try {
            // Do NOT decode the URL. OkHttp expects the raw encoded URL.
            // Decoding can break signatures (e.g. '+' becoming ' ').
            val finalUrl = url
            val downloadUri = finalUrl.toUri()
            val scheme = downloadUri.scheme?.lowercase()
            if (scheme == "blob") {
                // blob: payloads need a blob reader that isn't wired up yet;
                // say so instead of claiming the link is invalid.
                AppMessages.show(context.getString(R.string.file_type_not_downloadable))
                return
            }
            if (scheme != "http" && scheme != "https" || downloadUri.host.isNullOrBlank()) {
                AppMessages.show(context.getString(R.string.invalid_link))
                return
            }

            val initialFileName = URLUtil.guessFileName(finalUrl, contentDisposition, mimeType)
            val startedAt = System.currentTimeMillis()
            val cookies = cookieManager.getCookie(finalUrl)

            // Normalize Origin & Referer (truncate to origin only)
            // Some hosts (e.g. Lulustream) return 403 if referer includes a path.
            val uri = (currentPageUrl ?: "").toUri()
            val origin = if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}" else null
            val referer = if (origin != null) "$origin/" else currentPageUrl

            val intent = DownloadService.createStartIntent(
                context = context,
                url = finalUrl,
                fileName = initialFileName,
                mimeType = mimeType,
                userAgent = userAgent,
                referer = referer,
                origin = origin,
                cookies = cookies,
                source = source
            )
            context.startForegroundService(intent)

            if (onDownloadStarted != null) {
                onDownloadStarted(StartedDownload(finalUrl, startedAt))
            } else {
                AppMessages.show(context.getString(R.string.download_starting))
            }
        } catch (e: Exception) {
            AppMessages.show(context.getString(R.string.error_starting_download, e.message.orEmpty()))
        }
    }
}
