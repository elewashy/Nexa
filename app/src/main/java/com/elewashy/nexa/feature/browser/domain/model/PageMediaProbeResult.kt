package com.elewashy.nexa.feature.browser.domain.model

/** What a tab's in-page media probe last said about the rendered page. */
sealed interface PageMediaProbeResult {

    /**
     * The tab's WebView cannot run the probe (no document-start script or
     * web message listener support), so page-level confirmation is impossible.
     */
    data object Unavailable : PageMediaProbeResult

    /**
     * The probe inspected the page identified by [contentKey] (see
     * `MediaPage.contentKey`; null when the reported URL is not a media page)
     * and found downloadable media in it or not.
     */
    data class Reported(val contentKey: String?, val hasMedia: Boolean) : PageMediaProbeResult
}
