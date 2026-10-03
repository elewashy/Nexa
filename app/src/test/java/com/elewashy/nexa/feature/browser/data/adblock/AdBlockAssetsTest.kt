package com.elewashy.nexa.feature.browser.data.adblock

import com.elewashy.nexa.feature.browser.data.adblock.engine.RedirectResources
import com.elewashy.nexa.feature.browser.data.adblock.engine.ScriptletCatalog
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps the bundled JS assets and the Kotlin catalogs in sync. */
class AdBlockAssetsTest {

    private val assetsDir = File("src/main/assets/adblock")

    @Test
    fun `every catalogued scriptlet is implemented by the bundled library and vice versa`() {
        val registered = File(assetsDir, "scriptlets").listFiles()!!
            .filter { it.name.endsWith(".js") }
            .flatMap { file -> REGISTRATION.findAll(file.readText()).map { it.groupValues[1] }.toList() }
        assertEquals("duplicate registrations", registered.size, registered.toSet().size)
        assertEquals(ScriptletCatalog.canonicalNames.toSet(), registered.toSet())
    }

    @Test
    fun `every redirect resource has a body`() {
        val emptyBodies = setOf("noop.txt", "noop.css", "empty")
        for (name in RedirectResources.MIME_TYPES.keys - emptyBodies) {
            val file = File(assetsDir, "redirects/$name")
            assertTrue("missing redirect asset $name", file.isFile && file.length() > 0)
        }
    }

    @Test
    fun `content script exposes the bridge placeholder and runs scriptlets from the payload`() {
        val content = File(assetsDir, "content.js").readText()
        assertTrue(content.contains("'__NEXA_BRIDGE__'"))
        assertTrue(content.contains("payload.scriptlets"))
    }

    @Test
    fun `comment stripping keeps code and string literals intact`() {
        val source = """
            /*
             * Header block.
             */
            var a = '/* not a comment */'; // trailing comments stay
              // indented line comment
            /** one-line doc */
            var re = /\/\*x/;

            function f() { return 1; }
        """.trimIndent()
        val stripped = AdBlockAssets.stripComments(source)
        assertEquals(
            "var a = '/* not a comment */'; // trailing comments stay\nvar re = /\\/\\*x/;\nfunction f() { return 1; }\n",
            stripped,
        )
        assertFalse(stripped.contains("Header block"))
    }

    private companion object {
        val REGISTRATION = Regex("""^S\['([^']+)'\]\s*=""", RegexOption.MULTILINE)
    }
}
