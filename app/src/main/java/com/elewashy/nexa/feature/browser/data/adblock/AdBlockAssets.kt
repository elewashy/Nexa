package com.elewashy.nexa.feature.browser.data.adblock

import android.content.Context
import android.util.Log
import com.elewashy.nexa.feature.browser.data.adblock.engine.RedirectResources
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bundled content-blocking assets: the document-start content script
 * (scriptlet library + cosmetic content script) and the neutered redirect
 * resources served in place of blocked scripts, images and media.
 *
 * Everything is read from the APK once and kept in memory: the assembled
 * content script (about 100 KB once comments are stripped) and, on first
 * use, each redirect resource (a few KB in total).
 */
@Singleton
class AdBlockAssets @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /**
     * Name under which the Java bridge is exposed to pages. Random per
     * process so pages cannot probe for a fixed global to detect the blocker;
     * the content script deletes the global as soon as it has a reference.
     */
    val bridgeName: String = "_" + randomToken(12)

    /** The assembled document-start script; built on first use (off the UI thread where possible). */
    val contentScript: String by lazy { assembleContentScript() }

    private val redirectBodies = ConcurrentHashMap<String, ByteArray>()

    /** Body and MIME type of redirect resource [name] (a canonical [RedirectResources] name), or null. */
    fun redirectResource(name: String): RedirectBody? {
        val mime = RedirectResources.MIME_TYPES[name] ?: return null
        if (name in EMPTY_RESOURCES) return RedirectBody(mime, EMPTY)
        val bytes = redirectBodies[name] ?: readAsset("$REDIRECTS_DIR/$name")?.also { redirectBodies[name] = it }
            ?: return null
        return RedirectBody(mime, bytes)
    }

    class RedirectBody(val mimeType: String, val bytes: ByteArray)

    private fun assembleContentScript(): String {
        val assets = context.assets
        val scriptlets = (assets.list(SCRIPTLETS_DIR) ?: emptyArray()).filter { it.endsWith(".js") }.sorted()
        val out = StringBuilder(96 * 1024)
        // One closure: the scriptlet registry `S` is private to the content
        // script and invisible to the page.
        out.append("(function(){'use strict';\n")
        for (file in scriptlets) {
            readAsset("$SCRIPTLETS_DIR/$file")?.let { out.append(stripComments(String(it, Charsets.UTF_8))).append('\n') }
        }
        val content = readAsset(CONTENT_SCRIPT)?.let { String(it, Charsets.UTF_8) }.orEmpty()
        out.append(stripComments(content).replace(BRIDGE_PLACEHOLDER, bridgeName))
        out.append("\n})();")
        return out.toString()
    }

    private fun readAsset(path: String): ByteArray? = try {
        context.assets.open(path).use { it.readBytes() }
    } catch (e: IOException) {
        Log.e(TAG, "Missing ad-block asset $path", e)
        null
    }

    companion object {
        private const val TAG = "AdBlockAssets"
        private const val SCRIPTLETS_DIR = "adblock/scriptlets"
        private const val REDIRECTS_DIR = "adblock/redirects"
        private const val CONTENT_SCRIPT = "adblock/content.js"
        private const val BRIDGE_PLACEHOLDER = "__NEXA_BRIDGE__"
        private val EMPTY = ByteArray(0)
        private val EMPTY_RESOURCES = setOf("noop.txt", "noop.css", "empty")

        private fun randomToken(length: Int): String {
            val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
            val random = SecureRandom()
            return buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
        }

        /**
         * Drops whole-line comments and blank lines to shrink the script that
         * every frame parses. Only lines that *start* with a comment token are
         * touched, so string and regex literals are never altered.
         */
        internal fun stripComments(source: String): String {
            val out = StringBuilder(source.length)
            var inBlock = false
            for (line in source.lineSequence()) {
                val trimmed = line.trim()
                if (inBlock) {
                    if (trimmed.endsWith("*/")) inBlock = false
                    continue
                }
                when {
                    trimmed.isEmpty() || trimmed.startsWith("//") -> Unit
                    trimmed.startsWith("/*") -> if (!trimmed.endsWith("*/")) inBlock = true
                    else -> out.append(line).append('\n')
                }
            }
            return out.toString()
        }
    }
}
