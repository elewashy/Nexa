package com.elewashy.nexa.core.localization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationResourceTest {
    private val resDir = Path.of("src/main/res")

    @Test
    fun supportedLocalesHaveCompleteStringCoverage() {
        val sourceKeys = stringNames(resDir.resolve("values/strings.xml"))

        listOf("values-ar", "values-fr").forEach { localeDir ->
            val localizedKeys = stringNames(resDir.resolve("$localeDir/strings.xml"))
            assertEquals("$localeDir must translate every source string", sourceKeys, localizedKeys)
        }
    }

    @Test
    fun supportedLocalesHaveCompletePluralCoverage() {
        val sourceKeys = pluralNames(resDir.resolve("values/strings.xml"))

        listOf("values-ar", "values-fr").forEach { localeDir ->
            val localizedKeys = pluralNames(resDir.resolve("$localeDir/strings.xml"))
            assertEquals("$localeDir must translate every source plural", sourceKeys, localizedKeys)
        }
    }

    @Test
    fun localizedResourcesDoNotDuplicateNonTranslatableStrings() {
        val nonTranslatableKeys = nonTranslatableStringNames(resDir.resolve("values/strings.xml"))

        listOf("values-ar", "values-fr").forEach { localeDir ->
            val localizedKeys = allStringNames(resDir.resolve("$localeDir/strings.xml"))
            assertTrue(
                "$localeDir must not duplicate non-translatable strings",
                localizedKeys.intersect(nonTranslatableKeys).isEmpty(),
            )
        }
    }

    @Test
    fun onlyProductionLocalesArePresent() {
        val localeDirs = Files.list(resDir).use { paths ->
            paths
                .filter { Files.isDirectory(it) }
                .map { it.fileName.toString() }
                .filter { it.matches(Regex("values-[a-z]{2}")) }
                .sorted()
                .toList()
        }

        assertEquals(listOf("values-ar", "values-fr"), localeDirs)
    }

    @Test
    fun defaultResourceLocaleIsEnglish() {
        val resourcesProperties = Files.readString(resDir.resolve("resources.properties"))
        assertTrue(resourcesProperties.lines().any { it.trim() == "unqualifiedResLocale=en" })
    }

    private fun stringNames(path: Path): Set<String> {
        val document = parse(path)
        val nodes = document.getElementsByTagName("string")

        return buildSet {
            for (index in 0 until nodes.length) {
                val item = nodes.item(index)
                val translatable = item.attributes.getNamedItem("translatable")?.nodeValue
                val name = item.attributes.getNamedItem("name")?.nodeValue
                if (name != null && translatable != "false") add(name)
            }
        }
    }

    private fun allStringNames(path: Path): Set<String> {
        val document = parse(path)
        val nodes = document.getElementsByTagName("string")

        return buildSet {
            for (index in 0 until nodes.length) {
                val name = nodes.item(index).attributes.getNamedItem("name")?.nodeValue
                if (name != null) add(name)
            }
        }
    }

    private fun nonTranslatableStringNames(path: Path): Set<String> {
        val document = parse(path)
        val nodes = document.getElementsByTagName("string")

        return buildSet {
            for (index in 0 until nodes.length) {
                val item = nodes.item(index)
                val translatable = item.attributes.getNamedItem("translatable")?.nodeValue
                val name = item.attributes.getNamedItem("name")?.nodeValue
                if (name != null && translatable == "false") add(name)
            }
        }
    }

    private fun pluralNames(path: Path): Set<String> {
        val document = parse(path)
        val nodes = document.getElementsByTagName("plurals")

        return buildSet {
            for (index in 0 until nodes.length) {
                val name = nodes.item(index).attributes.getNamedItem("name")?.nodeValue
                if (name != null) add(name)
            }
        }
    }

    private fun parse(path: Path) =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(path.toFile())
}
