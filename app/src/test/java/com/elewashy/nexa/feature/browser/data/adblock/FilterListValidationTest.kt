package com.elewashy.nexa.feature.browser.data.adblock

import com.elewashy.nexa.feature.browser.data.resources.BrowserResourceId
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FilterListValidationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun file(content: String): File = temporaryFolder.newFile().apply { writeText(content) }

    @Test
    fun `accepts filter list syntax`() {
        assertTrue(AdBlockRepository.isValidFilterList(BrowserResourceId.EasyList, file("[Adblock Plus 2.0]\n||ads.example^\n")))
        assertTrue(AdBlockRepository.isValidFilterList(BrowserResourceId.PeterLoweList, file("# hosts\n127.0.0.1 ads.example\n")))
        assertTrue(AdBlockRepository.isValidFilterList(BrowserResourceId.EasyList, file("\uFEFF! Title: x\n##.ad\n")))
    }

    @Test
    fun `rejects html error and captive portal pages`() {
        assertFalse(AdBlockRepository.isValidFilterList(BrowserResourceId.EasyList, file("<!DOCTYPE html><html>")))
        assertFalse(AdBlockRepository.isValidFilterList(BrowserResourceId.UBlockFilters, file("\n  <html><body>Login</body>")))
    }

    @Test
    fun `only Nexa's own list may be empty`() {
        assertTrue(AdBlockRepository.isValidFilterList(BrowserResourceId.NexaFilters, file("")))
        assertFalse(AdBlockRepository.isValidFilterList(BrowserResourceId.EasyList, file("")))
    }

    @Test
    fun `regional lists follow the UI language`() {
        assertFalse(BrowserResourceId.ListeAr in BrowserResourceId.enabledFor("en"))
        assertTrue(BrowserResourceId.ListeAr in BrowserResourceId.enabledFor("ar"))
        assertTrue(BrowserResourceId.EasyList in BrowserResourceId.enabledFor("fr"))
    }
}
