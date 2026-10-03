package com.elewashy.nexa.feature.browser.data.adblock.engine

/**
 * Resource types understood by network filters, encoded as bit flags so a
 * filter's `$type` options and a request's types compare with a single AND.
 *
 * Android WebView does not expose Chromium's resource type to
 * `shouldInterceptRequest`, so [RequestTypeResolver] infers it from request
 * headers and the URL. A request gets one *primary* type, which blocking
 * filters match against, plus *alternative* types it might also be, which
 * only widen exception filters. Uncertainty therefore never makes a
 * type-specific block filter (e.g. EasyPrivacy's `*$ping,third-party`)
 * apply to a request of another type, while type-specific exceptions from
 * unbreak lists still apply.
 */
object RequestType {
    const val DOCUMENT = 1
    const val SUBDOCUMENT = 1 shl 1
    const val STYLESHEET = 1 shl 2
    const val SCRIPT = 1 shl 3
    const val IMAGE = 1 shl 4
    const val FONT = 1 shl 5
    const val MEDIA = 1 shl 6
    const val OBJECT = 1 shl 7
    const val XHR = 1 shl 8
    const val PING = 1 shl 9
    const val WEBSOCKET = 1 shl 10
    const val OTHER = 1 shl 11
    const val POPUP = 1 shl 12

    /** Types a filter without explicit type options applies to (uBO/ABP semantics). */
    const val DEFAULT_TYPES = SUBDOCUMENT or STYLESHEET or SCRIPT or IMAGE or FONT or MEDIA or
        OBJECT or XHR or PING or WEBSOCKET or OTHER

    /** `$all`: every type including top-level documents and popups. */
    const val ALL = DEFAULT_TYPES or DOCUMENT or POPUP

    /** Maps filter option names (including uBO/AdGuard aliases) to type bits. */
    fun fromOption(name: String): Int = when (name) {
        "document", "doc" -> DOCUMENT
        "subdocument", "frame" -> SUBDOCUMENT
        "stylesheet", "css" -> STYLESHEET
        "script" -> SCRIPT
        "image" -> IMAGE
        "font" -> FONT
        "media" -> MEDIA
        "object", "object-subrequest" -> OBJECT
        "xmlhttprequest", "xhr" -> XHR
        "ping", "beacon" -> PING
        "websocket" -> WEBSOCKET
        "other" -> OTHER
        "popup", "popunder" -> POPUP
        else -> 0
    }
}

/** A request's inferred type: the [primary] type and the [alternatives] it might also be. */
class ResolvedType(val primary: Int, val alternatives: Int = 0) {
    override fun toString(): String = "ResolvedType(primary=$primary, alternatives=$alternatives)"
}

/**
 * Infers the [RequestType] of WebView subresource requests from the signals
 * Chromium leaves in the request:
 *  - `Accept` is destination-specific for documents, stylesheets and images;
 *  - an `Origin` header on a GET marks a CORS request (fetch/XHR, fonts,
 *    `crossorigin` / module scripts) — classic `<script>` loads are no-cors;
 *  - a cross-origin no-cors `*／*` GET is a classic script (images, styles
 *    and frames announce themselves through `Accept`); a same-origin one is
 *    usually a fetch/XHR (Chromium omits `Origin` on same-origin GETs);
 *  - a `Range` header marks media streaming;
 *  - only `<a ping>` (`Content-Type: text/ping`) is reliably a ping; other
 *    uploads, including `navigator.sendBeacon`, resolve to XHR because
 *    beacons are indistinguishable from fetch POSTs.
 */
object RequestTypeResolver {

    private val WEBSOCKET = ResolvedType(RequestType.WEBSOCKET)
    private val SUBDOCUMENT = ResolvedType(RequestType.SUBDOCUMENT)
    private val STYLESHEET = ResolvedType(RequestType.STYLESHEET)
    private val IMAGE = ResolvedType(RequestType.IMAGE)
    private val SCRIPT = ResolvedType(RequestType.SCRIPT, RequestType.XHR)
    private val XHR = ResolvedType(RequestType.XHR)
    private val MEDIA = ResolvedType(RequestType.MEDIA, RequestType.XHR)
    private val PING = ResolvedType(RequestType.PING)
    private val UPLOAD = ResolvedType(RequestType.XHR, RequestType.PING or RequestType.OTHER)
    private val CORS_UNKNOWN = ResolvedType(RequestType.XHR, RequestType.SCRIPT or RequestType.OTHER)
    // Chromium always sends `Origin` on cross-origin fetch/XHR, so a cross-origin
    // request without it can never be an XHR: XHR-only exceptions must not allow it.
    private val NO_CORS_CROSS_ORIGIN = ResolvedType(RequestType.SCRIPT, RequestType.OTHER)
    private val NO_CORS_SAME_ORIGIN = ResolvedType(RequestType.XHR, RequestType.SCRIPT or RequestType.OTHER)

    /**
     * @param url full request URL.
     * @param accept `Accept` request header.
     * @param hasRange whether a `Range` header is present.
     * @param method HTTP method.
     * @param hasOrigin whether an `Origin` header is present.
     * @param contentType `Content-Type` request header.
     * @param sameOrigin whether the request targets the issuing document's
     *   origin; null when unknown.
     */
    fun resolve(
        url: String,
        accept: String?,
        hasRange: Boolean,
        method: String = "GET",
        hasOrigin: Boolean = false,
        contentType: String? = null,
        sameOrigin: Boolean? = null,
    ): ResolvedType {
        if (url.startsWith("ws:", ignoreCase = true) || url.startsWith("wss:", ignoreCase = true)) return WEBSOCKET
        if (accept != null) {
            when {
                accept.startsWith("text/html") || accept.startsWith("application/xhtml") -> return SUBDOCUMENT
                accept.startsWith("text/css") -> return STYLESHEET
                accept.startsWith("image/") -> return IMAGE
                accept.startsWith("application/javascript") || accept.startsWith("text/javascript") -> return SCRIPT
                accept.startsWith("application/json") -> return XHR
                accept.startsWith("video/") || accept.startsWith("audio/") -> return MEDIA
            }
        }
        if (contentType != null && contentType.startsWith("text/ping", ignoreCase = true)) return PING
        val readOnly = method.equals("GET", ignoreCase = true) || method.equals("HEAD", ignoreCase = true)
        if (!readOnly) return UPLOAD

        val byExtension = fromExtension(url)
        if (byExtension != 0) {
            return when {
                // Scripts and fonts keep their type when fetched in CORS mode.
                // Without `Origin` only a same-origin request may also be a fetch/XHR.
                !hasOrigin -> ResolvedType(byExtension, if (sameOrigin == true) RequestType.XHR else 0)
                byExtension == RequestType.SCRIPT || byExtension == RequestType.FONT ->
                    ResolvedType(byExtension, RequestType.XHR)
                // A CORS fetch of a manifest, segment or JSON (hls.js, players) is an XHR.
                else -> ResolvedType(RequestType.XHR, byExtension)
            }
        }
        if (hasRange) return MEDIA
        if (hasOrigin) return CORS_UNKNOWN
        return if (sameOrigin == true) NO_CORS_SAME_ORIGIN else NO_CORS_CROSS_ORIGIN
    }

    private fun fromExtension(url: String): Int {
        val ext = extensionOf(url) ?: return 0
        return when (ext) {
            "js", "mjs" -> RequestType.SCRIPT
            "css" -> RequestType.STYLESHEET
            "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico", "bmp", "apng" -> RequestType.IMAGE
            "woff", "woff2", "ttf", "otf", "eot" -> RequestType.FONT
            "mp4", "webm", "m3u8", "mpd", "m4s", "m4a", "m4v", "mp3", "ogg", "oga", "ogv", "aac", "flac",
            "wav", "ts", "mkv", "mov", "vtt" -> RequestType.MEDIA
            "json" -> RequestType.XHR
            "swf" -> RequestType.OBJECT
            else -> 0
        }
    }

    /** Lowercase extension of the URL path (without query/fragment), or null. */
    internal fun extensionOf(url: String): String? {
        val schemeEnd = url.indexOf("://")
        val pathStart = if (schemeEnd == -1) 0 else url.indexOf('/', schemeEnd + 3)
        if (pathStart == -1) return null
        var end = url.length
        for (i in pathStart until url.length) {
            val c = url[i]
            if (c == '?' || c == '#') {
                end = i
                break
            }
        }
        val slash = url.lastIndexOf('/', end - 1)
        val dot = url.lastIndexOf('.', end - 1)
        if (dot <= slash || dot == end - 1 || end - dot > 6) return null
        return url.substring(dot + 1, end).lowercase()
    }
}
