package com.elewashy.nexa.feature.browser.data.adblock

import com.elewashy.nexa.feature.browser.data.adblock.engine.FilterEngine
import com.elewashy.nexa.feature.browser.data.adblock.engine.Hostnames
import com.elewashy.nexa.feature.browser.data.adblock.engine.MatchResult

/**
 * What the content script reported right before a new window was requested:
 * which frame asked for it and how.
 */
class PopupHint(
    /** URL of the document (frame) that opened the window. */
    val frameUrl: String,
    /** Absolute target URL, or empty for `window.open()` / `about:blank`. */
    val targetUrl: String,
    val kind: Kind,
    val atMillis: Long,
) {
    enum class Kind {
        /** A trusted (user-generated) click on a link with a new-window target. */
        LINK,

        /** `window.open()` or a script-dispatched click on a `target=_blank` link. */
        SCRIPT;

        companion object {
            fun fromWire(value: String?): Kind = if (value == "link") LINK else SCRIPT
        }
    }
}

/**
 * Short-lived store of [PopupHint]s. The content script reports synchronously
 * through the Java bridge before the native window request reaches
 * `onCreateWindow`, so a hint is at most a few hundred milliseconds old when
 * it is consumed. Thread-safe.
 */
class PopupHints(private val clock: () -> Long = System::currentTimeMillis) {
    private val hints = ArrayDeque<PopupHint>()

    fun record(frameUrl: String, targetUrl: String, kind: PopupHint.Kind) {
        if (!frameUrl.startsWith("http")) return
        synchronized(hints) {
            hints.addLast(PopupHint(frameUrl, targetUrl, kind, clock()))
            while (hints.size > MAX_HINTS) hints.removeFirst()
        }
    }

    /**
     * Removes and returns the newest fresh hint for [targetUrl]. A hint with
     * an empty target (`window.open('')` navigated afterwards) matches any URL.
     */
    fun take(targetUrl: String): PopupHint? = synchronized(hints) {
        val now = clock()
        hints.removeAll { now - it.atMillis > TTL_MS }
        val match = hints.lastOrNull { it.targetUrl == targetUrl }
            ?: hints.lastOrNull { it.targetUrl.isEmpty() || it.targetUrl == "about:blank" }
        if (match != null) hints.remove(match)
        match
    }

    private companion object {
        const val MAX_HINTS = 16
        const val TTL_MS = 5_000L
    }
}

/**
 * Decides whether a new window (popup) may open.
 *
 * WebView has no tab strip of its own, so a popup either becomes a new app
 * tab or is discarded — it never replaces the opener page (that is what
 * produced block-page loops and ads "replacing" pages). Order of evaluation:
 *
 *  1. Filters, like uBO: `$popup` filters (in the opener frame's and the top
 *     page's context) and `$document` / strict hostname filters on the target.
 *     A popup to a blocked page is closed silently instead of showing the
 *     block page.
 *  2. A link the user actually tapped opens — detected either from the
 *     content script's hint (a trusted click on an `<a target=_blank>`) or
 *     from the WebView hit-test URL under the user's last tap.
 *  3. Script-opened windows (the popunder mechanism) are only allowed when
 *     the target is same-site as the opener. Ad networks rotate popunder
 *     domains faster than lists can follow, but a popunder always opens from
 *     the opener to a *foreign* site; a page's legitimate popups (social
 *     sharing, OAuth, "watch on <host>" from an embedded player) stay on
 *     its own site. This rule covers both an embedded iframe opening across
 *     sites and the top page itself doing so from a click on a non-link
 *     element — mycima.bid, filemoon mirrors and similar sites. It also
 *     covers windows opened without a content-script hint (the page evaded
 *     our `window.open` wrapper, or a frame we never saw).
 */
object PopupPolicy {

    class Decision(val allow: Boolean, val reason: String) {
        override fun toString(): String = (if (allow) "allow" else "block") + " ($reason)"
    }

    /**
     * @param topUrl URL of the opener tab's top document.
     * @param target the popup's first real URL.
     * @param hint what the content script reported for this popup, if anything.
     * @param tappedLinkUrl URL of the link under the user's last tap (WebView hit test), if any.
     */
    fun decide(
        engine: FilterEngine,
        topUrl: String?,
        target: String,
        hint: PopupHint?,
        tappedLinkUrl: String?,
    ): Decision {
        if (Hostnames.hostOf(target) == null) return Decision(false, "not a web page")
        val userLink = hint?.kind == PopupHint.Kind.LINK || (tappedLinkUrl != null && tappedLinkUrl == target)

        val openerUrl = hint?.frameUrl ?: topUrl
        val contexts = listOfNotNull(openerUrl, topUrl).distinct().map(engine::pageContext)
        for (context in contexts) {
            val popup = engine.matchPopup(target, context, userGesture = userLink)
            if (popup.shouldBlock) return Decision(false, "popup filter ${popup.filterText}")
            if (popup.decision == MatchResult.Decision.ALLOW || engine.isPopupAllowlisted(target, context)) {
                return Decision(true, "popup exception")
            }
        }
        val document = engine.matchDocument(target)
        if (document.shouldBlock) return Decision(false, "document filter ${document.filterText}")

        if (userLink) return Decision(true, "user link")
        if (topUrl == null) return Decision(true, "no opener page")

        // Script popup: allowed only when it stays on the opener's own site.
        // That covers social sharing, OAuth, and a video player's "open on
        // <host>" buttons; cross-site script popups are popunders.
        val frameUrl = hint?.frameUrl
        val openerSite = frameUrl ?: topUrl
        if (engine.isSameSite(target, openerSite)) return Decision(true, "same site as opener")
        val openerIsTop = frameUrl == null || engine.isSameSite(frameUrl, topUrl)
        val reason = when {
            frameUrl == null -> "unattributed script popup"
            openerIsTop -> "cross-site script popup from the page"
            else -> "cross-site popup from embedded frame"
        }
        return Decision(false, reason)
    }
}
