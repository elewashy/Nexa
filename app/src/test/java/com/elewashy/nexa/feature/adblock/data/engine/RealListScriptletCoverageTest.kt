package com.elewashy.nexa.feature.adblock.data.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Fails when a scriptlet used by the real default filter lists is not
 * supported, so list updates that start relying on a new scriptlet surface
 * here instead of silently losing filters.
 *
 *  - Always: every name in `real-list-scriptlets.txt` (a snapshot of the
 *    scriptlets the lists' blocking filters use, with the trust level of the
 *    lists using them) must resolve in [ScriptletCatalog] *and* be
 *    implemented by the bundled JS library.
 *  - With `NEXA_FILTER_LISTS_DIR` pointing at downloaded lists: every
 *    scriptlet filter line must compile. The only accepted exception is a
 *    trusted-only scriptlet requested by an untrusted list, which uBO
 *    rejects too.
 */
class RealListScriptletCoverageTest {

    private val implemented: Set<String> by lazy {
        val registration = Regex("""^S\['([^']+)'\]\s*=""", RegexOption.MULTILINE)
        File("src/main/assets/adblock/scriptlets").listFiles()!!
            .filter { it.name.endsWith(".js") }
            .flatMap { file -> registration.findAll(file.readText()).map { it.groupValues[1] }.toList() }
            .toSet()
    }

    @Test
    fun `every scriptlet named in the real lists snapshot is supported`() {
        val snapshot = javaClass.classLoader!!.getResourceAsStream("adblock/real-list-scriptlets.txt")!!
            .bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split('\t').let { it[0] to (it[1] == "trusted") } }
        assertTrue("snapshot unexpectedly small", snapshot.size > 80)

        val missing = snapshot.mapNotNull { (name, trusted) ->
            val canonical = ScriptletCatalog.resolve(name, trustedSource = trusted)
            when {
                canonical == null -> "$name (${if (trusted) "trusted" else "untrusted"} list): not in ScriptletCatalog"
                canonical !in implemented -> "$name → $canonical: not implemented in assets/adblock/scriptlets"
                else -> null
            }
        }
        assertTrue("Unsupported scriptlets:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    @Test
    fun `every scriptlet filter of the downloaded lists compiles`() {
        val dir = System.getenv("NEXA_FILTER_LISTS_DIR")?.let(::File)
        assumeTrue("NEXA_FILTER_LISTS_DIR not set", dir?.isDirectory == true)
        val failures = ArrayList<String>()
        var checkedLists = 0
        for (file in dir!!.listFiles()!!.filter { it.isFile }.sortedBy { it.name }) {
            val trusted = file.name in TRUSTED_LISTS
            val builder = FilterEngine.Builder(HeuristicRegistrableDomainResolver)
            file.bufferedReader().use { reader ->
                builder.addList(reader, trusted) { line ->
                    val name = scriptletName(line) ?: return@addList
                    val rejectedByTrust = !trusted && ScriptletCatalog.resolve(name, trustedSource = true) != null
                    if (!rejectedByTrust) failures += "${file.name}: $line"
                }
            }
            checkedLists++
        }
        assertTrue("no lists in $dir", checkedLists > 0)
        assertTrue(
            "${failures.size} unsupported scriptlet filters:\n" + failures.take(50).joinToString("\n") { it.take(300) },
            failures.isEmpty(),
        )
    }

    /** Scriptlet name of a `##+js(…)` / `#%#//scriptlet(…)` line, or null for other filters. */
    private fun scriptletName(line: String): String? {
        val raw = UBO.find(line)?.groupValues?.get(1) ?: ADGUARD.find(line)?.groupValues?.get(1) ?: return null
        return raw.trim().removeSuffix(".js")
    }

    private companion object {
        val UBO = Regex("""#@?#\+js\(\s*([^,)]*)""")
        val ADGUARD = Regex("""#@?%#//scriptlet\(\s*['"]([^'"]+)""")
        val TRUSTED_LISTS = setOf(
            "filters.min.txt", "badware.min.txt", "privacy.min.txt", "quick-fixes.min.txt", "unbreak.min.txt",
        )
    }
}
