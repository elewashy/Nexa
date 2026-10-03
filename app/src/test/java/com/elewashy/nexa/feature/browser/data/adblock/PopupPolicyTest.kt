package com.elewashy.nexa.feature.browser.data.adblock

import com.elewashy.nexa.feature.browser.data.adblock.engine.FilterEngine
import com.elewashy.nexa.feature.browser.data.adblock.engine.HeuristicRegistrableDomainResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupPolicyTest {

    private val engine: FilterEngine = FilterEngine.Builder(HeuristicRegistrableDomainResolver).apply {
        listOf(
            "||runative-syndicate.com^",
            "||popads.example^\$popup",
            "@@||allowed-popup.example^\$popup,domain=movies.example",
        ).joinToString("\n").byteInputStream().bufferedReader().use { addList(it, trusted = false) }
    }.build()

    private val top = "https://movies.example/watch/1"
    private val player = "https://streamwish.example/e/abc"

    private fun hint(frame: String, target: String, kind: PopupHint.Kind = PopupHint.Kind.SCRIPT) =
        PopupHint(frame, target, kind, 0L)

    private fun decide(target: String, hint: PopupHint?, tapped: String? = null) =
        PopupPolicy.decide(engine, top, target, hint, tapped)

    @Test
    fun `popups to blocked documents are dropped instead of showing the block page`() {
        val url = "https://runative-syndicate.com/api/v1/direct/x"
        assertFalse(decide(url, hint(top, url)).allow)
        // Even a tapped link to it opens nothing: the block page would replace no page.
        assertFalse(decide(url, hint(player, url, PopupHint.Kind.LINK)).allow)
    }

    @Test
    fun `popup filters apply in the opener frame and page contexts`() {
        assertFalse(decide("https://popads.example/x", hint(top, "https://popads.example/x")).allow)
        assertTrue(decide("https://allowed-popup.example/x", hint(player, "https://allowed-popup.example/x")).allow)
    }

    @Test
    fun `script popups from an embedded player to a foreign site are blocked`() {
        // Rotating popunder domains are not in any list.
        val ad = "https://cacklegrievingtank.com/2078233/?var=9tnz630s2a"
        assertFalse(decide(ad, hint(player, ad)).allow)
        // Script-dispatched clicks on target=_blank links count as scripts.
        assertFalse(decide(ad, hint(player, ad, PopupHint.Kind.SCRIPT)).allow)
    }

    @Test
    fun `embedded frames may open their own site and links the user tapped`() {
        assertTrue(decide("https://www.streamwish.example/download", hint(player, "https://www.streamwish.example/download")).allow)
        assertTrue(decide("https://news.example/story", hint(player, "https://news.example/story", PopupHint.Kind.LINK)).allow)
        assertTrue(decide("https://news.example/story", hint = null, tapped = "https://news.example/story").allow)
    }

    @Test
    fun `script popups the top page opens cross-site are blocked popunders`() {
        // mycima.bid: a trusted click on a non-link element invokes
        // window.open('https://asiafilm.org/4/…', '_blank') from the top page.
        val ad = "https://asiafilm.org/4/c35583a431f148109fbda514debbf829"
        assertFalse(decide(ad, hint(top, ad)).allow)
        // A tap on an unrelated element never resolves to the target URL via the hit test.
        assertFalse(decide(ad, hint(top, ad), tapped = "https://movies.example/other").allow)
    }

    @Test
    fun `the page may open popups on its own site and user links anywhere`() {
        // Social sharing / OAuth / self-navigation on the same registrable domain.
        assertTrue(decide("https://cdn.movies.example/share", hint(top, "https://cdn.movies.example/share")).allow)
        assertTrue(decide("https://cdn.movies.example/share", hint = null).allow)
        // The hit test confirms the user tapped a link to the target.
        assertTrue(decide("https://accounts.example/oauth", hint(top, "https://accounts.example/oauth"), tapped = "https://accounts.example/oauth").allow)
        // Content-script LINK hint (a trusted click on an <a target=_blank>) is always a user link.
        assertTrue(decide("https://outbound.example/x", hint(top, "https://outbound.example/x", PopupHint.Kind.LINK)).allow)
        // Unknown-opener cross-site popups stay blocked.
        assertFalse(decide("https://unknown-ads.example/x", hint = null).allow)
        assertFalse(decide("about:blank", hint(top, "")).allow)
    }

    @Test
    fun `hints match by target, fall back to blank-window hints and expire`() {
        var now = 1_000L
        val hints = PopupHints { now }
        hints.record(top, "https://a.example/", PopupHint.Kind.SCRIPT)
        hints.record(player, "", PopupHint.Kind.SCRIPT)
        assertEquals(top, hints.take("https://a.example/")?.frameUrl)
        assertEquals(player, hints.take("https://later-navigated.example/")?.frameUrl)
        assertNull(hints.take("https://a.example/"))
        hints.record(top, "https://b.example/", PopupHint.Kind.LINK)
        now += 10_000L
        assertNull(hints.take("https://b.example/"))
        hints.record("about:blank", "https://c.example/", PopupHint.Kind.LINK)
        assertNull(hints.take("https://c.example/"))
    }
}
