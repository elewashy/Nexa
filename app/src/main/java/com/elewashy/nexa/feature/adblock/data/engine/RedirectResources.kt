package com.elewashy.nexa.feature.adblock.data.engine

/**
 * Catalog of uBO redirect resources (names and aliases). A `redirect=`
 * filter naming an unknown resource is discarded at parse time, as uBO
 * does, rather than degrading into a plain block.
 */
object RedirectResources {

    /** Canonical name → MIME type. Content is served by the Android layer. */
    val MIME_TYPES: Map<String, String> = mapOf(
        "noop.js" to "application/javascript",
        "noop.txt" to "text/plain",
        "noop.html" to "text/html",
        "noop.css" to "text/css",
        "noop.json" to "application/json",
        "empty" to "text/plain",
        "1x1.gif" to "image/gif",
        "2x2.png" to "image/png",
        "3x2.png" to "image/png",
        "32x32.png" to "image/png",
        "noop-0.1s.mp3" to "audio/mpeg",
        "noop-0.5s.mp3" to "audio/mpeg",
        "noop-1s.mp4" to "video/mp4",
        "noop-vast2.xml" to "application/xml",
        "noop-vast3.xml" to "application/xml",
        "noop-vast4.xml" to "application/xml",
        "noop-vmap1.xml" to "application/xml",
        "google-ima.js" to "application/javascript",
        "googletagmanager_gtm.js" to "application/javascript",
        "googletagservices_gpt.js" to "application/javascript",
        "google-analytics_analytics.js" to "application/javascript",
        "google-analytics_ga.js" to "application/javascript",
        "googlesyndication_adsbygoogle.js" to "application/javascript",
        "amazon_apstag.js" to "application/javascript",
        "scorecardresearch_beacon.js" to "application/javascript",
        "fingerprint2.js" to "application/javascript",
        "nobab.js" to "application/javascript",
        "nobab2.js" to "application/javascript",
        "nofab.js" to "application/javascript",
        "noeval.js" to "application/javascript",
        "noeval-silent.js" to "application/javascript",
        "prebid-ads.js" to "application/javascript",
        "popads.js" to "application/javascript",
        "chartbeat.js" to "application/javascript",
        "outbrain-widget.js" to "application/javascript",
        "addthis_widget.js" to "application/javascript",
        "adthrive_abd.js" to "application/javascript",
        "amazon_ads.js" to "application/javascript",
        "ampproject_v0.js" to "application/javascript",
        "doubleclick_instream_ad_status.js" to "application/javascript",
        "fingerprint3.js" to "application/javascript",
        "google-analytics_cx_api.js" to "application/javascript",
        "google-analytics_inpage_linkid.js" to "application/javascript",
        "hd-main.js" to "application/javascript",
        "nitropay_ads.js" to "application/javascript",
        "popads-dummy.js" to "application/javascript",
        "sensors-analytics.js" to "application/javascript",
    )

    private val ALIASES: Map<String, String> = mapOf(
        "noopjs" to "noop.js",
        "blank-js" to "noop.js",
        "nooptext" to "noop.txt",
        "blank-text" to "noop.txt",
        "noopframe" to "noop.html",
        "blank-html" to "noop.html",
        "noopcss" to "noop.css",
        "blank-css" to "noop.css",
        "noopjson" to "noop.json",
        "1x1-transparent.gif" to "1x1.gif",
        "blank-gif" to "1x1.gif",
        "2x2-transparent.png" to "2x2.png",
        "blank-png" to "2x2.png",
        "3x2-transparent.png" to "3x2.png",
        "32x32-transparent.png" to "32x32.png",
        "noopmp3-0.1s" to "noop-0.1s.mp3",
        "blank-mp3" to "noop-0.1s.mp3",
        "noopmp4-1s" to "noop-1s.mp4",
        "blank-mp4" to "noop-1s.mp4",
        "noopvast-2.0" to "noop-vast2.xml",
        "noopvast-3.0" to "noop-vast3.xml",
        "noop-vast3.xml" to "noop-vast3.xml",
        "noopvast-4.0" to "noop-vast4.xml",
        "noopvmap-1.0" to "noop-vmap1.xml",
        "noop-vmap1.0.xml" to "noop-vmap1.xml",
        "google-ima3" to "google-ima.js",
        "googletagmanager.com/gtm.js" to "googletagmanager_gtm.js",
        "googletagservices.com/gpt.js" to "googletagservices_gpt.js",
        "googletagservices_gpt" to "googletagservices_gpt.js",
        "google-analytics.com/analytics.js" to "google-analytics_analytics.js",
        "googletagmanager_gtag.js" to "google-analytics_analytics.js",
        "google-analytics.com/ga.js" to "google-analytics_ga.js",
        "googlesyndication.com/adsbygoogle.js" to "googlesyndication_adsbygoogle.js",
        "amazon-adsystem.com/aax2/amzn_ads.js" to "amazon_ads.js",
        "amazon-adsystem.com/aax2/apstag.js" to "amazon_apstag.js",
        "addthis.com/addthis_widget.js" to "addthis_widget.js",
        "ampproject.org/v0.js" to "ampproject_v0.js",
        "doubleclick.net/instream/ad_status.js" to "doubleclick_instream_ad_status.js",
        "google-analytics.com/cx/api.js" to "google-analytics_cx_api.js",
        "google-analytics.com/inpage_linkid.js" to "google-analytics_inpage_linkid.js",
        "scorecardresearch.com/beacon.js" to "scorecardresearch_beacon.js",
        "bab-defuser.js" to "nobab.js",
        "fuckadblock.js-3.2.0" to "nofab.js",
        "silent-noeval.js" to "noeval-silent.js",
        "popads.net.js" to "popads.js",
        "static.chartbeat.com/chartbeat.js" to "chartbeat.js",
        "widgets.outbrain.com/outbrain.js" to "outbrain-widget.js",
    )

    /** `redirect-rule=none`: a priority marker that cancels lower-priority redirects (plain block). */
    const val NONE = "none"

    fun canonicalName(name: String): String? {
        if (name in MIME_TYPES || name == NONE) return name
        return ALIASES[name]
    }
}
