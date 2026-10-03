package com.elewashy.nexa.feature.browser.data.adblock

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import java.io.File

/**
 * One-time removal of the data written by the previous host-set blocker
 * (merged host files, per-list host caches, the valid-links allowlist, the
 * injected pre/post-load scripts and their metadata). None of it is read by
 * the filter engine; leaving it would only waste storage.
 */
internal object LegacyAdBlockData {
    private const val TAG = "LegacyAdBlockData"
    private const val PREFS_NAME = "AdBlockerPrefs"
    private const val KEY_DONE = "legacy_data_removed_v2"

    private val LEGACY_RESOURCE_NAMES = listOf(
        "InternalAdFilters", "ValidLinks", "PreLoadScript", "PostLoadScript", "OneHostsLite",
    )
    private val LEGACY_RESOURCE_FILES = listOf(
        "filters/blocklist.txt", "filters/allowlist.txt", "filters/external_easylist.txt",
        "filters/external_easyprivacy.txt", "filters/external_1hosts_lite.txt",
    )

    fun delete(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return
        try {
            val filesDir = context.filesDir
            filesDir.listFiles { _, name ->
                name == "ad_hosts.txt" || name == "ad_hosts.txt.tmp" || name == "valid_links.txt" ||
                    (name.startsWith("hosts_") && (name.endsWith(".txt") || name.endsWith(".txt.tmp")))
            }?.forEach { it.delete() }
            val resources = File(filesDir, "browser_resources")
            LEGACY_RESOURCE_FILES.forEach { File(resources, it).delete() }
            File(resources, "scripts").deleteRecursively()

            context.deleteSharedPreferences("ValidLinkCheckerPrefs")
            val metadata = context.getSharedPreferences("BrowserResourceMetadata", Context.MODE_PRIVATE)
            val staleKeys = metadata.all.keys.filter { key ->
                LEGACY_RESOURCE_NAMES.any { key.startsWith(it + "_") } ||
                    // EasyList / EasyPrivacy moved to new cache files; their old
                    // validators would otherwise answer 304 for a file we no longer have.
                    key.startsWith("EasyList_") || key.startsWith("EasyPrivacy_")
            }
            metadata.edit { staleKeys.forEach(::remove) }
            prefs.edit {
                clear()
                putBoolean(KEY_DONE, true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Legacy ad-block data cleanup failed: ${e.message}")
        }
    }
}
