package com.elewashy.nexa.feature.browser.data.links

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import com.elewashy.nexa.feature.browser.data.resources.BrowserResourceId
import com.elewashy.nexa.feature.browser.data.resources.BrowserResourceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ValidLinkRepository — high-performance whitelist checker for valid/trusted
 * domains.
 *
 * Architecture:
 * - Flat-file storage for domain lists (fast read/write, low memory)
 * - BrowserResourceRepository owns remote downloads and cache validation
 * - Background parsing only when cached resources change
 * - Efficient domain matching with HashSet lookups
 *
 * Performance Optimizations:
 * - Flat-file storage instead of SharedPreferences for bulk domains
 * - indexOf()-based parsing instead of split() (halves GC pressure)
 * - HashSet O(1) lookups with parent-domain walk
 * - Sequenced init: load-from-disk first
 *
 * Thread Safety:
 * - All public methods are thread-safe
 * - @Volatile reference swapped atomically
 * - Concurrent reads without blocking
 */
@Singleton
class ValidLinkRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val resourceRepository: BrowserResourceRepository,
) {

    private data class ValidLinkSnapshot(
        val globalHosts: Set<String> = emptySet(),
        val siteScopedHosts: Map<String, Set<String>> = emptyMap(),
    ) {
        val totalRuleCount: Int
            get() = globalHosts.size + siteScopedHosts.size
    }

    private data class ParsedValidLinkRule(
        val host: String,
        val allowedSites: Set<String>?,
    )

    // ========== Constants ==========

    companion object {
        private const val TAG = "ValidLinkRepository"
        private val HOST_LABEL_REGEX = Regex("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$")

        // SharedPreferences (metadata only)
        private const val PREFS_NAME = "ValidLinkCheckerPrefs"

        // Flat-file storage
        private const val LINKS_FILE_NAME = "valid_links.txt"

        // EasyList parsing
        private const val EASYLIST_COMMENT_PREFIX = "!"
        private const val EASYLIST_EXCEPTION_PREFIX = "@@||"
        private const val EASYLIST_DOMAIN_PREFIX = "||"
        private const val EASYLIST_DOMAIN_SUFFIX_CHAR = '^'
        private const val ALL_SITES_TOKEN = "all"
    }

    // ========== State Management ==========

    /**
     * Immutable snapshot of valid domains.
     * Writes swap the entire reference atomically (@Volatile).
     */
    @Volatile
    private var validLinkSnapshot = ValidLinkSnapshot()
    
    private val linksFile = File(context.filesDir, LINKS_FILE_NAME)

    /**
     * SharedPreferences for metadata only (not for bulk data).
     */
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ========== Initialization ==========

    init {
        loadLocalLinksAsync()
    }

    /**
     * Loads domains from flat file asynchronously.
     * Non-blocking initialization.
     */
    private fun loadLocalLinksAsync() {
        Thread({
            try {
                val loaded = loadLocalLinks()
                validLinkSnapshot = loaded
                Log.d(TAG, "Loaded ${loaded.totalRuleCount} valid link rules from storage")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading valid links", e)
            }
        }, "ValidLinkChecker-load").apply {
            priority = Thread.NORM_PRIORITY - 2
            isDaemon = true
        }.start()
    }

    // ========== Public API ==========

    /**
     * Checks if URL is a valid/trusted link
     * Thread-safe, O(depth) where depth ≈ 3-4
     */
    fun isValidLink(url: String): Boolean {
        return try {
            val host = url.toUri().host ?: return false
            isValidHost(host)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking URL: $url", e)
            false
        }
    }

    /**
     * Checks if host is a valid/trusted domain.
     * Uses parent-domain walk — O(depth) instead of O(N).
     */
    fun isValidHost(host: String): Boolean {
        return try {
            val normalizedHost = normalizeHost(host) ?: return false
            containsHost(validLinkSnapshot.globalHosts, normalizedHost)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking host: $host", e)
            false
        }
    }

    /**
     * Checks if host is globally allowed or allowed for a specific page host.
     * Site-scoped rules use the format `||request-host^$site1.com,site2.com`.
     */
    fun isValidHostOnPage(host: String, pageHost: String?): Boolean {
        return try {
            val normalizedHost = normalizeHost(host) ?: return false
            val snapshot = validLinkSnapshot

            if (containsHost(snapshot.globalHosts, normalizedHost)) {
                return true
            }

            val normalizedPageHost = normalizeHost(pageHost) ?: return false
            isSiteScopedMatch(snapshot.siteScopedHosts, normalizedHost, normalizedPageHost)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking scoped valid host: $host on $pageHost", e)
            false
        }
    }

    fun getValidLinkCount(): Int = validLinkSnapshot.totalRuleCount

    /**
     * Updates valid links from remote source.
     * Called from background thread in SplashActivity.
     */
    fun updateValidLinks(): Boolean {
        return try {
            Log.d(TAG, "Starting valid links update")
            performUpdate()
        } catch (e: Exception) {
            Log.e(TAG, "Valid links update failed", e)
            false
        }
    }

    // ========== Update Logic ==========

    private fun performUpdate(): Boolean {
        return try {
            val currentSnapshot = validLinkSnapshot
            val result = resourceRepository.refresh(BrowserResourceId.ValidLinks)
            if (!result.updated && currentSnapshot.totalRuleCount > 0) {
                Log.d(TAG, "Valid links unchanged. Skipping parse.")
                return true
            }

            val newLinks = parseLinks(resourceRepository.fileFor(BrowserResourceId.ValidLinks))

            if (newLinks.totalRuleCount > 0 && newLinks != currentSnapshot) {
                validLinkSnapshot = newLinks // Atomic swap
                saveLocalLinks(newLinks)
                Log.d(TAG, "Valid links updated successfully: ${newLinks.totalRuleCount} rules")
                true
            } else {
                Log.d(TAG, "No changes in valid links. Skipping update.")
                currentSnapshot.totalRuleCount > 0 || newLinks.totalRuleCount > 0
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating valid links", e)
            validLinkSnapshot.totalRuleCount > 0
        }
    }

    // ========== Network Operations ==========

    /**
     * Stream-parses links directly from network response.
     * Uses indexOf() instead of split() to halve GC pressure.
     */
    private fun parseLinks(file: File): ValidLinkSnapshot {
        if (!file.exists() || file.length() == 0L) return ValidLinkSnapshot()
        val globalHosts = HashSet<String>(1024)
        val siteScopedHosts = HashMap<String, MutableSet<String>>()
        file.bufferedReader().use { reader ->
            reader.forEachLine { line -> addParsedRule(line, globalHosts, siteScopedHosts) }
        }
        return buildSnapshot(globalHosts, siteScopedHosts)
    }

    // ========== Storage ==========

    /**
     * Saves valid links to flat file — one domain per line.
     * Uses atomic tmp→rename to prevent partial writes on crash.
     */
    private fun saveLocalLinks(links: ValidLinkSnapshot) {
        try {
            val tmpFile = File(linksFile.parentFile, "${LINKS_FILE_NAME}.tmp")
            tmpFile.bufferedWriter().use { writer ->
                for (link in serializeRules(links)) {
                    writer.write(link)
                    writer.newLine()
                }
            }
            tmpFile.renameTo(linksFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving valid links", e)
        }
    }

    /**
     * Loads valid links from flat file.
     */
    private fun loadLocalLinks(): ValidLinkSnapshot {
        if (!linksFile.exists()) {
            // Migrate from SharedPreferences if flat file doesn't exist yet
            return migrateFromSharedPreferences()
        }
        return try {
            val globalHosts = HashSet<String>(512)
            val siteScopedHosts = HashMap<String, MutableSet<String>>()
            linksFile.bufferedReader().use { reader ->
                reader.lineSequence()
                    .forEach { line -> addParsedRule(line, globalHosts, siteScopedHosts) }
            }
            buildSnapshot(globalHosts, siteScopedHosts)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading valid links", e)
            ValidLinkSnapshot()
        }
    }

    /**
     * One-time migration from SharedPreferences to flat file.
     */
    private fun migrateFromSharedPreferences(): ValidLinkSnapshot {
        return try {
            val oldLinks = sharedPreferences.getStringSet("validLinks", emptySet()) ?: emptySet()
            if (oldLinks.isNotEmpty()) {
                val globalHosts = HashSet<String>(oldLinks.size)
                val siteScopedHosts = HashMap<String, MutableSet<String>>()
                oldLinks.forEach { line -> addParsedRule(line, globalHosts, siteScopedHosts) }
                val links = buildSnapshot(globalHosts, siteScopedHosts)
                saveLocalLinks(links)
                sharedPreferences.edit { remove("validLinks") }
                Log.d(TAG, "Migrated ${links.totalRuleCount} valid link rules from SharedPreferences to flat file")
                links
            } else {
                ValidLinkSnapshot()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Migration from SharedPreferences failed", e)
            ValidLinkSnapshot()
        }
    }

    private fun buildSnapshot(
        globalHosts: Set<String>,
        siteScopedHosts: Map<String, Set<String>>,
    ): ValidLinkSnapshot {
        return ValidLinkSnapshot(
            globalHosts = globalHosts.toSet(),
            siteScopedHosts = siteScopedHosts.mapValues { (_, allowedSites) -> allowedSites.toSet() },
        )
    }

    private fun addParsedRule(
        line: String,
        globalHosts: MutableSet<String>,
        siteScopedHosts: MutableMap<String, MutableSet<String>>,
    ) {
        val parsedRule = parseRule(line) ?: return
        val allowedSites = parsedRule.allowedSites

        if (allowedSites == null) {
            globalHosts.add(parsedRule.host)
            siteScopedHosts.remove(parsedRule.host)
            return
        }

        if (parsedRule.host in globalHosts) return

        val scopedSites = siteScopedHosts.getOrPut(parsedRule.host) {
            HashSet(allowedSites.size)
        }
        scopedSites.addAll(allowedSites)
    }

    private fun parseRule(line: String): ParsedValidLinkRule? {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith(EASYLIST_COMMENT_PREFIX)) {
            return null
        }

        val ruleBody = when {
            trimmed.startsWith(EASYLIST_EXCEPTION_PREFIX) -> {
                trimmed.substring(EASYLIST_EXCEPTION_PREFIX.length)
            }
            trimmed.startsWith(EASYLIST_DOMAIN_PREFIX) -> {
                trimmed.substring(EASYLIST_DOMAIN_PREFIX.length)
            }
            else -> trimmed
        }

        if (ruleBody.isBlank()) return null

        val hostEnd = ruleBody.indexOfFirst {
            it == EASYLIST_DOMAIN_SUFFIX_CHAR || it == '$'
        }.let { index -> if (index == -1) ruleBody.length else index }

        val host = parseSupportedHost(ruleBody.substring(0, hostEnd)) ?: return null
        val optionsStart = ruleBody.indexOf('$', hostEnd)
        if (optionsStart == -1) {
            return ParsedValidLinkRule(host, null)
        }

        val allowedSites = parseAllowedSites(ruleBody.substring(optionsStart + 1))
        if (allowedSites == null) {
            return ParsedValidLinkRule(host, null)
        }
        if (allowedSites.isEmpty()) {
            return null
        }

        return ParsedValidLinkRule(host, allowedSites)
    }

    private fun parseAllowedSites(options: String): Set<String>? {
        val trimmedOptions = options.trim()
        if (trimmedOptions.isBlank()) return emptySet()
        if (trimmedOptions.equals(ALL_SITES_TOKEN, ignoreCase = true)) {
            return null
        }

        val allowedSites = LinkedHashSet<String>()
        val domainOption = trimmedOptions.split(',')
            .firstOrNull { token -> token.trim().startsWith("domain=", ignoreCase = true) }

        val siteTokens = if (domainOption != null) {
            domainOption.substringAfter('=')
                .split('|')
        } else {
            trimmedOptions.split(',')
        }

        siteTokens.forEach { token ->
            val normalizedToken = token.trim()
            if (normalizedToken.isBlank() || normalizedToken.startsWith("~")) {
                return@forEach
            }
            if (normalizedToken.equals(ALL_SITES_TOKEN, ignoreCase = true)) {
                return null
            }
            if (!normalizedToken.contains('.')) {
                return@forEach
            }

            val normalizedHost = parseSupportedHost(normalizedToken)
            if (normalizedHost != null) {
                allowedSites.add(normalizedHost)
            }
        }

        return allowedSites
    }

    private fun serializeRules(snapshot: ValidLinkSnapshot): Set<String> {
        val serializedRules = LinkedHashSet<String>(snapshot.totalRuleCount)
        serializedRules.addAll(snapshot.globalHosts.sorted())

        snapshot.siteScopedHosts.toSortedMap().forEach { (host, allowedSites) ->
            serializedRules.add(
                buildString {
                    append(host)
                    append('$')
                    append(allowedSites.sorted().joinToString(","))
                }
            )
        }

        return serializedRules
    }

    private fun containsHost(hosts: Set<String>, host: String): Boolean {
        if (hosts.contains(host)) return true

        var dotIndex = host.indexOf('.')
        while (dotIndex != -1) {
            val parent = host.substring(dotIndex + 1)
            if (parent.indexOf('.') == -1) break
            if (hosts.contains(parent)) return true
            dotIndex = host.indexOf('.', dotIndex + 1)
        }

        return false
    }

    private fun isSiteScopedMatch(
        siteScopedHosts: Map<String, Set<String>>,
        requestHost: String,
        pageHost: String,
    ): Boolean {
        var candidateHost: String? = requestHost
        while (candidateHost != null) {
            val allowedSites = siteScopedHosts[candidateHost]
            if (allowedSites != null && containsHost(allowedSites, pageHost)) {
                return true
            }

            val dotIndex = candidateHost.indexOf('.')
            if (dotIndex == -1) break

            val parent = candidateHost.substring(dotIndex + 1)
            candidateHost = parent.takeIf { it.contains('.') }
        }

        return false
    }

    private fun parseSupportedHost(hostPattern: String): String? {
        val trimmedPattern = hostPattern.trim()
        if (trimmedPattern.isBlank() ||
            trimmedPattern.startsWith('.') ||
            trimmedPattern.startsWith('/') ||
            trimmedPattern.contains('/') ||
            trimmedPattern.contains('*') ||
            trimmedPattern.contains('%') ||
            trimmedPattern.contains('?')
        ) {
            return null
        }

        val normalizedHost = normalizeHost(trimmedPattern) ?: return null
        if (!normalizedHost.contains('.')) return null

        val labels = normalizedHost.split('.')
        if (labels.size < 2 || labels.any { !HOST_LABEL_REGEX.matches(it) }) {
            return null
        }

        return normalizedHost
    }

    private fun normalizeHost(host: String?): String? {
        if (host.isNullOrBlank()) return null

        val normalizedHost = host.trim()
            .removePrefix(EASYLIST_DOMAIN_PREFIX)
            .substringBefore(EASYLIST_DOMAIN_SUFFIX_CHAR)
            .substringBefore('/')
            .substringBefore(':')
            .trim('.')
            .lowercase()

        return normalizedHost.takeIf { it.isNotBlank() }
    }
}
