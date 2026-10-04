package com.elewashy.nexa.feature.adblock.data

import com.elewashy.nexa.feature.adblock.domain.model.SiteAdBlockSettings
import com.elewashy.nexa.feature.adblock.presentation.SiteAdBlockMenuViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockPolicyTest {

    private val policy = AdBlockPolicy.of(
        globalEnabled = true,
        sites = listOf(
            SiteAdBlockSettings("example.com", blockingEnabled = false),
            SiteAdBlockSettings("news.example.com", cosmeticFilteringEnabled = false),
            SiteAdBlockSettings("video.test", popupBlockingEnabled = false),
        ),
    )

    @Test
    fun `site setting covers subdomains and the most specific one wins`() {
        assertFalse(policy.forHost("example.com").filtering)
        assertFalse(policy.forHost("www.example.com").filtering)
        val news = policy.forHost("m.news.example.com")
        assertTrue(news.filtering)
        assertFalse(news.cosmeticFiltering)
        assertTrue(news.popupBlocking)
    }

    @Test
    fun `unrelated hosts use the defaults`() {
        assertEquals(SitePolicy.DEFAULT, policy.forHost("notexample.com"))
        assertEquals(SitePolicy.DEFAULT, policy.forHost("example.com.evil.net"))
        assertEquals(SitePolicy.DEFAULT, policy.forHost(null))
    }

    @Test
    fun `global switch off disables everything`() {
        val off = AdBlockPolicy.of(globalEnabled = false, sites = emptyList())
        assertEquals(SitePolicy.OFF, off.forUrl("https://any.example/"))
    }

    @Test
    fun `turning blocking off for a site also stops cosmetics and popup blocking`() {
        val p = policy.forUrl("https://www.example.com/page")
        assertEquals(SitePolicy.OFF, p)
    }

    @Test
    fun `site keys normalize user input and page urls`() {
        assertEquals("example.com", SiteSettingsRepository.siteKey("https://www.Example.com/path?q=1"))
        assertEquals("example.com", SiteSettingsRepository.siteKey("example.com"))
        assertEquals("sub.example.com", SiteSettingsRepository.siteKey("sub.example.com:8080"))
        assertEquals("192.168.1.1", SiteSettingsRepository.siteKey("http://192.168.1.1/"))
        assertNull(SiteSettingsRepository.siteKey("not a site"))
        assertNull(SiteSettingsRepository.siteKey("localhost"))
        assertNull(SiteSettingsRepository.siteKey(""))
    }

    @Test
    fun `menu switch edits the setting that governs the page`() {
        val state = SiteAdBlockMenuViewModel.menuState("https://cdn.example.com/x", policy)!!
        assertEquals("example.com", state.site)
        assertFalse(state.siteEnabled)
        assertTrue(state.globalEnabled)

        val other = SiteAdBlockMenuViewModel.menuState("https://www.other.org/", policy)!!
        assertEquals("other.org", other.site)
        assertTrue(other.siteEnabled)
    }

    @Test
    fun `menu offers no switch for pages that are not web sites`() {
        assertNull(SiteAdBlockMenuViewModel.menuState(null, policy))
        assertNull(SiteAdBlockMenuViewModel.menuState("about:blank", policy))
        assertNull(SiteAdBlockMenuViewModel.menuState("file:///sdcard/a.html", policy))
    }
}
