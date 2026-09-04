package com.elewashy.nexa.feature.browser.data.adblock

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.elewashy.nexa.R
import com.elewashy.nexa.core.notifications.NotificationChannels
import com.elewashy.nexa.feature.browser.data.resources.BrowserResourceId
import com.elewashy.nexa.feature.browser.data.resources.BrowserResourceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AdBlockRepository — high-performance ad blocking using EasyList filters.
 *
 * Hilt singleton for ad-block host matching and parsed filter storage.
 *
 * Architecture:
 * - Singleton (@Singleton) provisioned by Hilt with the application Context
 * - Flat-file storage for bulk hosts (fast read/write, low memory)
 * - BrowserResourceRepository owns remote downloads, cache validation, and raw filters
 * - Background parsing updates with notifications
 * - Efficient host matching with HashSet lookups
 *
 * Performance Optimizations:
 * - Flat-file storage instead of SharedPreferences for 50k-200k hosts
 * - Stream-parsing from cached filter files
 * - indexOf()-based parsing instead of split() (halves GC pressure)
 * - HashSet O(1) lookups with parent-domain walk
 * - Sequenced init: load-from-disk completes before update starts
 * - Pre-sized HashSet to avoid rehashing
 *
 * Features:
 * - Nexa, EasyList, EasyPrivacy, and 1Hosts Lite filters
 * - Conditional HTTP requests (ETag/Last-Modified) via BrowserResourceRepository
 * - Progress notifications
 * - Auto-dismiss notifications
 * - Persistent storage
 *
 * Thread Safety:
 * - All public methods are thread-safe
 * - @Volatile adHosts reference swapped atomically
 * - Concurrent reads without blocking
 * - Single init thread ensures load-before-update ordering
 */
@Singleton
class AdBlockRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val resourceRepository: BrowserResourceRepository,
) {

    // ========== Constants ==========

    companion object {
        private const val TAG = "AdBlockRepository"

        // SharedPreferences (metadata only — NOT for bulk hosts)
        private const val PREFS_NAME = "AdBlockerPrefs"

        // Flat-file storage for bulk hosts
        private const val HOSTS_FILE_NAME = "ad_hosts.txt"

        // Notification
        private const val CHANNEL_ID = NotificationChannels.ADBLOCK
        private const val NOTIFICATION_UPDATE = 1
        private const val NOTIFICATION_AUTO_DISMISS_DELAY = 3000L

        private val AD_BLOCK_RESOURCES: List<BrowserResourceId> = BrowserResourceId.adBlockFilters

        // EasyList parsing
        private const val EASYLIST_COMMENT_PREFIX = "!"
        private const val EASYLIST_EXCEPTION_PREFIX = "@@||"
        private const val EASYLIST_DOMAIN_PREFIX = "||"
        private const val EASYLIST_DOMAIN_SUFFIX_CHAR = '^'

        /** Structural characters that end the domain part of a rule. */
        private val DOMAIN_TERMINATORS = charArrayOf(
            EASYLIST_DOMAIN_SUFFIX_CHAR, '/', '?', '$', '#', ':'
        )

        /**
         * Parses a single EasyList line and adds the normalized host to [hosts].
         * Pure function — testable without Android.
         *
         * Skips comments (`!`), exceptions (`@@||`), cosmetic/option lines
         * (anything containing a comma) and blank/malformed rules.
         */
        internal fun addHostRule(line: String, hosts: MutableSet<String>) {
            if (!line.startsWith(EASYLIST_DOMAIN_PREFIX) ||
                line.startsWith(EASYLIST_COMMENT_PREFIX) ||
                line.startsWith(EASYLIST_EXCEPTION_PREFIX) ||
                line.contains(",")
            ) {
                return
            }

            val start = EASYLIST_DOMAIN_PREFIX.length
            // Terminate at the first structural char so rules like
            // ||x.com/path or ||x.com$third-party don't swallow the
            // path/options into the host set.
            val endIdx = line.indexOfAny(DOMAIN_TERMINATORS, start)
            val domain = if (endIdx != -1) line.substring(start, endIdx) else line.substring(start)
            // Hosts are matched lowercase and www-stripped (normalizeHost);
            // rules must be stored the same way or they never match.
            val normalizedDomain = domain.lowercase().removePrefix("www.")
            if (normalizedDomain.isNotBlank()) hosts.add(normalizedDomain)
        }

        /**
         * Checks [host] against [hosts]: exact match first, then a parent-domain
         * walk (e.g. "ads.tracker.example.com" → "tracker.example.com",
         * "example.com"). The walk stops before single-label parents — bare
         * TLDs are never queried. Pure function — testable without Android.
         */
        internal fun isAdHost(host: String, hosts: Set<String>): Boolean {
            // Fast path: Direct exact match — O(1) via HashSet
            if (hosts.contains(host)) return true

            // Parent-domain walk: e.g. "ads.tracker.example.com"
            //   → checks "tracker.example.com", "example.com"
            //   (stops before single-label parents — bare TLDs are never queried)
            var dotIndex = host.indexOf('.')
            while (dotIndex != -1) {
                val parentDomain = host.substring(dotIndex + 1)
                if (parentDomain.indexOf('.') == -1) break
                if (hosts.contains(parentDomain)) return true
                dotIndex = host.indexOf('.', dotIndex + 1)
            }

            return false
        }
    }

    // ========== State Management ==========

    /**
     * Immutable snapshot of blocked hosts.
     * Writes swap the entire reference atomically (@Volatile),
     * so readers never see a half-built set.
     */
    @Volatile
    private var adHosts: Set<String> = emptySet()

    /**
     * SharedPreferences for small metadata only (timestamps, ETags).
     * Bulk host data is stored in flat files — see [hostsFile].
     */
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Flat file for persisting the merged host set (one domain per line).
     * Dramatically faster than SharedPreferences XML for 50k-200k entries.
     */
    private val hostsFile = File(context.filesDir, HOSTS_FILE_NAME)

    /**
     * Notification manager for update notifications
     */
    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Guards the update pipeline. The singleton init and the splash use case
     * both trigger a refresh on launch; background passes skip instead of
     * duplicating work, manual (forced) passes wait their turn.
     */
    private val updateLock = ReentrantLock()

    /**
     * Shared by every update pass. Passes are serialised by [updateLock], so
     * one pool is enough — reusing it avoids creating and tearing down threads
     * on every refresh. Never shut down: singleton lifetime.
     */
    private val updateExecutor: ExecutorService =
        Executors.newFixedThreadPool(minOf(AD_BLOCK_RESOURCES.size, 3))

    // ========== Initialization ==========

    init {
        // Create notification channel (lightweight IPC)
        createNotificationChannel()

        // Single thread: load from disk only. Refresh passes are triggered by
        // the splash InitializeBlocklistsUseCase — triggering them here as well
        // ran two serialized full update passes on every launch whose hourly
        // gate had expired.
        Thread({
            try {
                sweepOrphanTmpFiles()
                val loaded = loadLocalHosts()
                adHosts = loaded
                Log.d(TAG, "Loaded ${loaded.size} hosts from storage")
            } catch (e: Exception) {
                Log.e(TAG, "Init error", e)
            }
        }, "AdBlocker-init").apply {
            priority = Thread.NORM_PRIORITY - 2
            isDaemon = true
        }.start()
    }

    /**
     * Removes `.tmp` halves of the atomic tmp→rename writes that a crash can
     * leave behind; nothing else sweeps them.
     */
    private fun sweepOrphanTmpFiles() {
        try {
            File(context.filesDir, "$HOSTS_FILE_NAME.tmp").delete()
            context.filesDir.listFiles { dir, name ->
                name.startsWith("hosts_") && name.endsWith(".tmp") && File(dir, name).isFile
            }?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "Tmp sweep failed: ${e.message}")
        }
    }

    /**
     * Creates notification channel for Android O+
     */
    private fun createNotificationChannel() {
        NotificationChannels.ensure(
            notificationManager = notificationManager,
            id = CHANNEL_ID,
            name = context.getString(R.string.adblock_channel_name),
            importance = NotificationChannels.IMPORTANCE_LOW,
            description = context.getString(R.string.adblock_channel_description),
            showBadge = false
        )
    }

    // ========== Public API ==========

    /**
     * Checks if URL should be blocked
     * Thread-safe with concurrent read access
     * O(1) average time complexity using HashSet
     *
     * @param url URL to check
     * @return true if URL should be blocked
     */
    fun isAd(url: String): Boolean {
        return try {
            val host = url.toUri().host ?: return false
            isAdHost(host)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking URL: $url", e)
            false
        }
    }

    /**
     * Checks if host should be blocked (optimized for repeated checks)
     * Use this when you already have the host extracted
     *
     * Performance: O(depth) where depth ≈ 3-4 (number of dots in host)
     *
     * @param host Host to check (e.g., "ads.example.com")
     * @return true if host should be blocked
     */
    fun isAdHost(host: String): Boolean {
        return try {
            isAdHost(host, adHosts)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking host: $host", e)
            false
        }
    }

    /**
     * Gets the total number of blocked hosts
     */
    fun getBlockedHostCount(): Int = adHosts.size

    /**
     * Downloads all ad-block lists in parallel. Notifications are only shown
     * when at least one list is actually updated. Bypasses the 6 h gate —
     * callers decide whether the update is due via [FilterTimestampStore].
     */
    fun updateAllAdBlockLists(): Boolean {
        return runAdBlockUpdate(force = true)
    }

    fun refreshDueAdBlockLists(): Boolean {
        return runAdBlockUpdate(force = false)
    }

    private fun runAdBlockUpdate(force: Boolean): Boolean {
        if (force) {
            // Manual updates always run; wait for an in-flight pass to finish.
            updateLock.lock()
        } else if (!updateLock.tryLock()) {
            Log.d(TAG, "Filter update already running; skipping duplicate refresh")
            return true
        }

        return try {
            performEasyListUpdate(force)
        } catch (e: Exception) {
            Log.e(TAG, "updateAllAdBlockLists failed", e)
            showErrorNotification(NOTIFICATION_UPDATE, context.getString(R.string.error_updating_adblock_lists))
            false
        } finally {
            updateLock.unlock()
        }
    }

    // ========== Update Logic ==========

    private fun performEasyListUpdate(force: Boolean): Boolean {
        val completedDownloads = AtomicInteger(0)
        val anyModified = AtomicBoolean(false)
        val totalLists = AD_BLOCK_RESOURCES.size
        val builder = createProgressNotificationBuilder()

        val futures = AD_BLOCK_RESOURCES.map { resource ->
            updateExecutor.submit(Callable<Pair<Boolean, Set<String>>> {
                try {
                    val (modified, hosts) = checkAndReadList(resource, force)
                    val progress = completedDownloads.incrementAndGet()

                    if (modified) anyModified.set(true)
                    // Only surface progress once an actual update is underway;
                    // routine checks stay silent.
                    if (anyModified.get()) {
                        updateProgressNotification(
                            builder,
                            NOTIFICATION_UPDATE,
                            progress,
                            totalLists,
                            modified
                        )
                    }

                    Pair(modified, hosts)
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading list: ${resource.name}", e)
                    completedDownloads.incrementAndGet()
                    Pair(false, emptySet<String>())
                }
            })
        }

        return try {
            val results: List<Pair<Boolean, Set<String>>> = futures.map { it.get() }
            val modifiedCount = results.count { it.first }
            val allHosts = results.flatMap { it.second }.toSet()

            if (allHosts.isNotEmpty()) {
                adHosts = allHosts

                if (modifiedCount > 0) {
                    saveLocalHosts(allHosts)
                    AD_BLOCK_RESOURCES.forEachIndexed { i, resource -> saveHostsForUrl(resource.name, results[i].second) }
                    showSuccessNotification(
                        NOTIFICATION_UPDATE,
                        context.resources.getQuantityString(R.plurals.updated_lists_count, modifiedCount, modifiedCount)
                    )
                    Log.d(TAG, "Filter update completed: $modifiedCount lists updated")
                } else {
                    // Nothing changed remotely: the merged file already holds
                    // this host set (it was loaded at init), so rewriting it
                    // and the per-list caches would be pure IO waste.
                    if (!hostsFile.exists()) saveLocalHosts(allHosts)
                    notificationManager.cancel(NOTIFICATION_UPDATE)
                    Log.d(TAG, "All filter lists up to date; no notification")
                }
                true
            } else {
                showErrorNotification(NOTIFICATION_UPDATE, context.getString(R.string.error_updating_adblock_lists))
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "EasyList update error", e)
            showErrorNotification(NOTIFICATION_UPDATE, context.getString(R.string.error_updating_adblock_lists))
            false
        }
    }

    // ========== Network Operations ==========

    /**
     * Checks if list is modified and downloads if needed.
     * Uses conditional HTTP requests (ETag/Last-Modified).
     */
    private fun checkAndReadList(resource: BrowserResourceId, force: Boolean): Pair<Boolean, Set<String>> {
        return try {
            val result = resourceRepository.refresh(resource, force)
            if (!result.updated) {
                // Remote content unchanged — serve the stored per-list hosts,
                // read lazily so the common no-update pass skips the parse.
                getStoredHostsForUrl(resource.name)?.let { return Pair(false, it) }
            }

            val hosts = parseHosts(resourceRepository.fileFor(resource))
            saveHostsForUrl(resource.name, hosts)
            if (!result.updated) {
                // Only the local per-list cache was rebuilt; the remote
                // content is unchanged, so this is not an update.
                return Pair(false, hosts)
            }

            Pair(true, hosts)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking/reading list: ${resource.name}", e)
            Pair(false, getStoredHostsForUrl(resource.name) ?: emptySet())
        }
    }

    private fun parseHosts(file: File): Set<String> {
        if (!file.exists() || file.length() == 0L) return emptySet()
        val hosts = HashSet<String>(65536)
        file.bufferedReader().use { reader ->
            reader.forEachLine { line ->
                addHostRule(line, hosts)
            }
        }
        return hosts
    }

    // ========== Storage ==========

    /**
     * Gets stored hosts for a specific list URL (per-URL flat file).
     */
    private fun getStoredHostsForUrl(url: String): Set<String>? {
        val file = File(context.filesDir, "hosts_${url.hashCode()}.txt")
        if (!file.exists()) return null
        return try {
            file.bufferedReader().use { reader ->
                reader.lineSequence()
                    .filter { it.isNotBlank() }
                    .toHashSet()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading hosts for $url", e)
            null
        }
    }

    /**
     * Saves hosts for a specific list URL to its own flat file.
     * Uses atomic tmp→rename to prevent partial writes on crash.
     */
    private fun saveHostsForUrl(url: String, hosts: Set<String>) {
        try {
            val file = File(context.filesDir, "hosts_${url.hashCode()}.txt")
            val tmpFile = File(context.filesDir, "hosts_${url.hashCode()}.txt.tmp")
            tmpFile.bufferedWriter().use { writer ->
                for (host in hosts) {
                    writer.write(host)
                    writer.newLine()
                }
            }
            tmpFile.renameTo(file)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving hosts for $url", e)
        }
    }

    /**
     * Saves merged hosts to the main flat file — one domain per line.
     * ~200k domains write in ~50ms (vs 1-2s for SharedPreferences XML).
     * Uses atomic tmp→rename to prevent partial writes on crash.
     */
    private fun saveLocalHosts(hosts: Set<String>) {
        try {
            val tmpFile = File(context.filesDir, "${HOSTS_FILE_NAME}.tmp")
            tmpFile.bufferedWriter().use { writer ->
                for (host in hosts) {
                    writer.write(host)
                    writer.newLine()
                }
            }
            tmpFile.renameTo(hostsFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving local hosts", e)
        }
    }

    /**
     * Loads hosts from the main flat file into a HashSet.
     * Uses lineSequence() to avoid loading the entire file into a single String.
     */
    private fun loadLocalHosts(): Set<String> {
        if (!hostsFile.exists()) {
            // Migrate from SharedPreferences if flat file doesn't exist yet
            return migrateFromSharedPreferences()
        }
        return try {
            hostsFile.bufferedReader().use { reader ->
                reader.lineSequence()
                    .filter { it.isNotBlank() }
                    .toHashSet()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading local hosts", e)
            emptySet()
        }
    }

    /**
     * One-time migration from SharedPreferences to flat file.
     * Reads from the old KEY_AD_HOSTS StringSet, saves to flat file, then clears.
     */
    private fun migrateFromSharedPreferences(): Set<String> {
        return try {
            val oldHosts = sharedPreferences.getStringSet("adHosts", emptySet()) ?: emptySet()
            if (oldHosts.isNotEmpty()) {
                val hosts = oldHosts.toHashSet()
                saveLocalHosts(hosts)
                // Remove from SharedPreferences to free the XML overhead
                sharedPreferences.edit { remove("adHosts") }
                Log.d(TAG, "Migrated ${hosts.size} hosts from SharedPreferences to flat file")
                hosts
            } else {
                emptySet()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Migration from SharedPreferences failed", e)
            emptySet()
        }
    }

    // ========== Notifications ==========

    /**
     * Builds the progress notification without posting it. Nothing is shown
     * unless an actual list update is detected during the pass.
     */
    private fun createProgressNotificationBuilder(): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle(context.getString(R.string.checking_adblock_lists))
            .setContentText(context.getString(R.string.checking_for_changes))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, 0, true)
            .setOngoing(true)
    }

    private fun updateProgressNotification(
        builder: NotificationCompat.Builder,
        notificationId: Int,
        current: Int,
        total: Int,
        modified: Boolean
    ) {
        val progress = (current * 100) / total
        val text = if (modified) {
            context.getString(R.string.downloading_updated_list_progress, current, total)
        } else {
            context.getString(R.string.checking_lists_progress, current, total)
        }

        // Parallel list tasks share the builder; NotificationCompat.Builder
        // is not thread-safe.
        synchronized(builder) {
            builder.setContentText(text)
                .setProgress(100, progress, false)
            notificationManager.notify(notificationId, builder.build())
        }
    }

    private fun showSuccessNotification(notificationId: Int, message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_update)
            .setContentTitle(context.getString(R.string.adblock_update))
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setTimeoutAfter(NOTIFICATION_AUTO_DISMISS_DELAY)
            .build()

        notificationManager.notify(notificationId, notification)
    }

    private fun showErrorNotification(notificationId: Int, message: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_error)
            .setContentTitle(context.getString(R.string.adblock_update_error))
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setTimeoutAfter(NOTIFICATION_AUTO_DISMISS_DELAY)
            .build()

        notificationManager.notify(notificationId, notification)
    }
}
