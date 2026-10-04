package com.elewashy.nexa.feature.adblock.data

import com.elewashy.nexa.feature.adblock.data.lists.BuiltInFilterList
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
        assertTrue(AdBlockRepository.isValidFilterList(BuiltInFilterList.EasyList, file("[Adblock Plus 2.0]\n||ads.example^\n")))
        assertTrue(AdBlockRepository.isValidFilterList(BuiltInFilterList.PeterLoweList, file("# hosts\n127.0.0.1 ads.example\n")))
        assertTrue(AdBlockRepository.isValidFilterList(BuiltInFilterList.EasyList, file("\uFEFF! Title: x\n##.ad\n")))
    }

    @Test
    fun `rejects html error and captive portal pages`() {
        assertFalse(AdBlockRepository.isValidFilterList(BuiltInFilterList.EasyList, file("<!DOCTYPE html><html>")))
        assertFalse(AdBlockRepository.isValidFilterList(BuiltInFilterList.UBlockFilters, file("\n  <html><body>Login</body>")))
    }

    @Test
    fun `only Nexa's own list may be empty`() {
        assertTrue(AdBlockRepository.isValidFilterList(BuiltInFilterList.NexaFilters, file("")))
        assertFalse(AdBlockRepository.isValidFilterList(BuiltInFilterList.EasyList, file("")))
    }

    @Test
    fun `regional lists are selected by default for their UI language`() {
        assertFalse(BuiltInFilterList.ListeAr in BuiltInFilterList.defaultsFor("en"))
        assertTrue(BuiltInFilterList.ListeAr in BuiltInFilterList.defaultsFor("ar"))
        assertTrue(BuiltInFilterList.EasyList in BuiltInFilterList.defaultsFor("fr"))
    }
}
